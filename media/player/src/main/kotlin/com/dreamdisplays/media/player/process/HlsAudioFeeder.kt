package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads a live HLS audio rendition on the JVM and pipes the raw MPEG-TS segments into the
 * audio `FFmpeg` process's stdin, so FFmpeg only demuxes and decodes — it never touches the network.
 *
 * Exists because the bundled macOS FFmpeg's SecureTransport TLS regularly hangs on its first
 * connection to live HLS hosts (Twitch `ttvnw.net`) until `rw_timeout` kills it and a reconnect
 * retries — 25-30 s with no PCM at all, and more stalls mid-stream. The JVM HTTP stack reaches the
 * same hosts instantly (the resolver and thumbnails already use it), so fetching segments here and
 * feeding them through a pipe removes the flaky layer entirely.
 *
 * Live-only: the feeder always joins near the live edge and follows the sliding window; a VOD
 * playlist (with its full segment list and `#EXT-X-ENDLIST`) must keep using FFmpeg's own `-ss`
 * URL input for seeking.
 */
internal class HlsAudioFeeder(
    private val playlistUrl: String,
    private val sink: OutputStream,
    private val stopFlag: AtomicBoolean,
    private val terminated: AtomicBoolean,
    private val debugLabel: String,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/HlsAudioFeeder")

    /**
     * PES PTS of the first audio access unit fed into the pipe, in nanos (90 kHz ticks converted),
     * or -1 until the first segment has been scanned. Twitch renditions share one segmenter, so
     * this is on the same stream clock as the video channel's raw LAV PTS — their difference is
     * the exact content offset between the two independently joined HLS streams, which the session
     * manager uses to anchor A/V pacing precisely instead of guessing from wall time.
     */
    @Volatile
    var firstPtsNanos: Long = -1L; private set

    /** Parsed live media playlist: the sliding segment window plus the tags the feeder needs. */
    private class MediaPlaylist(
        @JvmField val mediaSequence: Long,
        @JvmField val targetDurationMs: Long,
        @JvmField val segments: List<String>,
        @JvmField val endList: Boolean,
    )

    /** Starts the feeder thread. The thread exits on stop/terminate, sink failure, or `#EXT-X-ENDLIST`. */
    fun start(): Thread = daemon(::run, "MediaPlayer-audio-hls").also { it.start() }

    private fun run() {
        var nextSeq = -1L
        var playlistFailures = 0
        var firstSegment = true
        try {
            while (alive()) {
                val playlist = try {
                    parse(DreamHttpClient.readText(playlistUrl, PLAYLIST_OPTIONS))
                } catch (e: IOException) {
                    if (!alive()) return
                    if (++playlistFailures > MAX_PLAYLIST_FAILURES) {
                        logger.warn("$debugLabel [audio-hls] playlist failed $playlistFailures times (${e.message}); giving up.")
                        return
                    }
                    sleepQuietly(PLAYLIST_RETRY_MS)
                    continue
                }
                playlistFailures = 0

                // Join (or re-join after falling out of the window) a few segments shy of the live
                // edge, mirroring FFmpeg's own HLS default, so audio content lines up with the video
                // channel that joined the same way.
                val edgeStart = playlist.mediaSequence + (playlist.segments.size - LIVE_EDGE_SEGMENTS).coerceAtLeast(0)
                if (nextSeq < playlist.mediaSequence) {
                    if (nextSeq >= 0) {
                        logger.warn(
                            "$debugLabel [audio-hls] fell behind the live window " +
                                    "(next=$nextSeq, window starts ${playlist.mediaSequence}); re-joining the edge."
                        )
                    }
                    nextSeq = edgeStart
                }

                var wroteAny = false
                var index = (nextSeq - playlist.mediaSequence).toInt()
                while (index >= 0 && index < playlist.segments.size && alive()) {
                    val segmentUrl = playlist.segments[index]
                    val bytes = try {
                        DreamHttpClient.readBytes(segmentUrl, SEGMENT_OPTIONS)
                    } catch (e: IOException) {
                        if (!alive()) return
                        // One segment lost is a minor audio blip; the next one keeps the stream going
                        logger.warn("$debugLabel [audio-hls] segment fetch failed (${e.message}); skipping one.")
                        nextSeq++; index++
                        continue
                    }
                    if (firstPtsNanos < 0) scanFirstAudioPts(bytes)
                    try {
                        sink.write(bytes) // Blocks on FFmpeg's stdin back-pressure; that pacing is intended
                    } catch (e: IOException) {
                        // FFmpeg exited or teardown closed the pipe — either way this feeder is done
                        if (alive()) logger.debug("$debugLabel [audio-hls] sink closed (${e.message}); stopping.")
                        return
                    }
                    if (firstSegment) {
                        firstSegment = false
                        logger.debug("$debugLabel [audio-hls] first segment piped (${bytes.size} B, seq=$nextSeq).")
                    }
                    wroteAny = true
                    nextSeq++; index++
                }

                if (playlist.endList) return
                if (!wroteAny) sleepQuietly((playlist.targetDurationMs / 2).coerceIn(500L, 2_000L))
            }
        } finally {
            runCatching { sink.flush() }
            runCatching { sink.close() } // EOF lets FFmpeg drain and end its PCM output cleanly
        }
    }

    /** Extracts the media sequence, target duration, segment URIs, and end marker from [body]. */
    private fun parse(body: String): MediaPlaylist {
        var mediaSequence = 0L
        var targetDurationMs = 2_000L
        var endList = false
        val segments = ArrayList<String>()
        val base = URI(playlistUrl)
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: mediaSequence

                line.startsWith("#EXT-X-TARGETDURATION:") ->
                    line.substringAfter(':').trim().toDoubleOrNull()
                        ?.let { targetDurationMs = (it * 1_000).toLong().coerceAtLeast(500L) }

                line == "#EXT-X-ENDLIST" -> endList = true
                line.startsWith("#") -> {} // Comments, Twitch daterange / prefetch tags, EXTINF durations
                else -> segments.add(base.resolve(line).toString())
            }
        }
        return MediaPlaylist(mediaSequence, targetDurationMs, segments, endList)
    }

    /**
     * Scans a raw MPEG-TS [segment] for the first audio PES header (stream ids `0xC0`..`0xDF`;
     * Twitch's `timed_id3` rides private stream `0xBD` and is skipped) and records its PTS into
     * [firstPtsNanos]. Walks 188-byte TS packets, honoring the adaptation field, and only looks at
     * packets that start a new PES payload.
     */
    private fun scanFirstAudioPts(segment: ByteArray) {
        var i = 0
        while (i + TS_PACKET_SIZE <= segment.size) {
            if (segment[i] != TS_SYNC_BYTE) {
                i++; continue
            } // Tolerate junk: re-sync byte-by-byte
            val payloadUnitStart = (segment[i + 1].toInt() and 0x40) != 0
            val adaptation = (segment[i + 3].toInt() shr 4) and 0x3
            if (!payloadUnitStart || adaptation == 2) {
                i += TS_PACKET_SIZE; continue
            }
            var p = i + 4
            if (adaptation == 3) p += 1 + (segment[i + 4].toInt() and 0xFF)
            // PES start code + stream id + flags + 5 PTS bytes must fit inside this TS packet
            if (p + 14 <= i + TS_PACKET_SIZE &&
                segment[p] == 0.toByte() && segment[p + 1] == 0.toByte() && segment[p + 2] == 1.toByte()
            ) {
                val streamId = segment[p + 3].toInt() and 0xFF
                val ptsDtsFlags = (segment[p + 7].toInt() shr 6) and 0x3
                if (streamId in 0xC0..0xDF && ptsDtsFlags >= 2) {
                    val b = { off: Int -> segment[p + 9 + off].toLong() and 0xFF }
                    val pts90k = (((b(0) shr 1) and 0x07) shl 30) or
                            (b(1) shl 22) or
                            (((b(2) shr 1) and 0x7F) shl 15) or
                            (b(3) shl 7) or
                            ((b(4) shr 1) and 0x7F)
                    firstPtsNanos = pts90k * 100_000L / 9L // 90 kHz ticks -> nanos
                    logger.debug(
                        "$debugLabel [audio-hls] first audio PTS ${"%.1f".format(firstPtsNanos / 1e6)} ms."
                    )
                    return
                }
            }
            i += TS_PACKET_SIZE
        }
    }

    private fun alive(): Boolean = !stopFlag.get() && !terminated.get()

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }.onFailure { e ->
            if (e !is InterruptedException) throw e
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TS_PACKET_SIZE = 188
        private const val TS_SYNC_BYTE = 0x47.toByte()

        /** How many segments shy of the live edge to join, matching FFmpeg's HLS default of -3. */
        private const val LIVE_EDGE_SEGMENTS = 3

        /** Consecutive playlist failures tolerated before the feeder gives up (URL expired / stream over). */
        private const val MAX_PLAYLIST_FAILURES = 5

        /** Pause between playlist retries after a fetch failure. */
        private const val PLAYLIST_RETRY_MS = 1_000L

        private val PLAYLIST_OPTIONS = DreamHttpClient.RequestOptions(
            connectTimeoutMs = 5_000L, readTimeoutMs = 5_000L, callTimeoutMs = 8_000L,
        )
        private val SEGMENT_OPTIONS = DreamHttpClient.RequestOptions(
            connectTimeoutMs = 5_000L, readTimeoutMs = 10_000L, callTimeoutMs = 15_000L,
        )

        /**
         * True when [url] looks like an HLS playlist this feeder can follow (Twitch live weaver
         * URLs carry no `.m3u8` suffix, hence the host check).
         */
        fun supports(url: String): Boolean = url.contains(".m3u8") || url.contains(".ttvnw.net/")
    }
}
