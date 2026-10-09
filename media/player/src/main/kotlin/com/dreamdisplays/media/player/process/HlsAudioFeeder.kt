package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads a live HLS audio rendition on the JVM and pipes its segments (MPEG-TS, or fragmented MP4 behind
 * their `EXT-X-MAP` init segment) into the audio `FFmpeg` process's stdin, so `FFmpeg` only demuxes and decodes.
 */
internal class HlsAudioFeeder(
    private val playlistUrl: String,
    private val sink: OutputStream,
    private val stopFlag: AtomicBoolean,
    private val terminated: AtomicBoolean,
    private val debugLabel: String,
    private val resumeSeq: Long = -1L,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    var firstPtsNanos: Long = -1L; private set

    @Volatile
    var sourceGone: Boolean = false; private set

    @Volatile
    var splicedAtSeq: Long = -1L; private set

    private val stopAtSplice = playlistUrl.contains(".ttvnw.net/")

    private class Segment(
        @JvmField val url: String,
        @JvmField val initUrl: String?,
        @JvmField val discontinuity: Boolean,
    )

    /** Parsed live media playlist: the sliding segment window plus the tags the feeder needs. */
    private class MediaPlaylist(
        @JvmField val mediaSequence: Long,
        @JvmField val targetDurationMs: Long,
        @JvmField val segments: List<Segment>,
        @JvmField val endList: Boolean,
    )

    /** Starts the feeder thread. The thread exits on stop/terminate, sink failure, or `#EXT-X-ENDLIST`. */
    fun start(): Thread = daemon(::run, "MediaPlayer-audio-hls").also { it.start() }

    private fun run() {
        var nextSeq = resumeSeq
        var playlistFailures = 0
        var segmentFailures = 0
        var firstSegment = true
        var pipedInitUrl: String? = null
        var initBytes: ByteArray? = null
        try {
            while (alive()) {
                val playlist = try {
                    parse(DreamHttpClient.readText(playlistUrl, PLAYLIST_OPTIONS))
                } catch (e: IOException) {
                    if (!alive()) return
                    if (++playlistFailures > MAX_PLAYLIST_FAILURES) {
                        logger.warn("$debugLabel [audio-hls] playlist failed $playlistFailures times (${e.message}); giving up.")
                        sourceGone = true
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
                if (nextSeq < playlist.mediaSequence || nextSeq > playlist.mediaSequence + playlist.segments.size) {
                    if (nextSeq >= 0) {
                        logger.warn(
                            "$debugLabel [audio-hls] fell out of the live window (next=$nextSeq, window " +
                                    "${playlist.mediaSequence}+${playlist.segments.size}); re-joining the edge."
                        )
                    }
                    nextSeq = edgeStart
                }

                var wroteAny = false
                var index = (nextSeq - playlist.mediaSequence).toInt()
                while (index >= 0 && index < playlist.segments.size && alive()) {
                    val segment = playlist.segments[index]
                    if (stopAtSplice && segment.discontinuity && !firstSegment) {
                        logger.debug("$debugLabel [audio-hls] ad splice at seq=$nextSeq; ending this session.")
                        splicedAtSeq = nextSeq
                        return
                    }
                    val newInit = segment.initUrl != pipedInitUrl
                    val bytes = try {
                        if (newInit) initBytes = segment.initUrl?.let { DreamHttpClient.readBytes(it, SEGMENT_OPTIONS) }
                        DreamHttpClient.readBytes(segment.url, SEGMENT_OPTIONS)
                    } catch (e: IOException) {
                        if (!alive()) return
                        if (++segmentFailures > MAX_SEGMENT_FAILURES) {
                            logger.warn(
                                "$debugLabel [audio-hls] $segmentFailures segments in a row failed " +
                                        "(${e.message}); giving up so the session re-resolves."
                            )
                            sourceGone = true
                            return
                        }
                        logger.warn("$debugLabel [audio-hls] segment fetch failed (${e.message}); skipping one.")
                        nextSeq++; index++
                        continue
                    }
                    segmentFailures = 0
                    if (firstPtsNanos < 0) {
                        firstPtsNanos = initBytes?.let { mp4FirstPtsNanos(it, bytes) } ?: tsFirstAudioPtsNanos(bytes)
                    }
                    try {
                        if (newInit) {
                            initBytes?.let { sink.write(it) }
                            pipedInitUrl = segment.initUrl
                        }
                        sink.write(bytes) // Blocks on FFmpeg's stdin back-pressure; that pacing is intended
                    } catch (e: IOException) {
                        // FFmpeg exited or teardown closed the pipe — either way this feeder is done
                        if (alive()) logger.debug("$debugLabel [audio-hls] sink closed (${e.message}); stopping.")
                        return
                    }
                    if (firstSegment) {
                        firstSegment = false
                        logger.debug(
                            "$debugLabel [audio-hls] first segment piped (${bytes.size} B, seq=$nextSeq, " +
                                    "pts=${firstPtsNanos / 1_000_000} ms)."
                        )
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

    /** Extracts the media sequence, target duration, segments, and end marker from [body]. */
    private fun parse(body: String): MediaPlaylist {
        var mediaSequence = 0L
        var targetDurationMs = 2_000L
        var endList = false
        var initUrl: String? = null
        var discontinuity = false
        val segments = ArrayList<Segment>()
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

                line.startsWith("#EXT-X-MAP:") ->
                    initUrl = line.substringAfter("URI=\"", "").substringBefore('"').takeIf { it.isNotEmpty() }
                        ?.let { base.resolve(it).toString() }

                line == "#EXT-X-DISCONTINUITY" -> discontinuity = true
                line == "#EXT-X-ENDLIST" -> endList = true
                line.startsWith("#") -> {} // Comments, Twitch daterange / prefetch tags, EXTINF durations
                else -> {
                    segments.add(Segment(base.resolve(line).toString(), initUrl, discontinuity))
                    discontinuity = false
                }
            }
        }
        return MediaPlaylist(mediaSequence, targetDurationMs, segments, endList)
    }

    /**
     * Scans a raw MPEG-TS [segment] for the first audio PES header (stream ids `0xC0`..`0xDF`; Twitch's `timed_id3`
     * stream is skipped) and returns its PTS in nanos, or -1 when there is none.
     */
    private fun tsFirstAudioPtsNanos(segment: ByteArray): Long {
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
                    return pts90k * 100_000L / 9L // 90 kHz ticks -> nanos
                }
            }
            i += TS_PACKET_SIZE
        }
        return -1L
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
        private const val LIVE_EDGE_SEGMENTS = 3
        private const val MAX_PLAYLIST_FAILURES = 5
        private const val MAX_SEGMENT_FAILURES = 3
        private const val PLAYLIST_RETRY_MS = 1_000L

        private val PLAYLIST_OPTIONS = DreamHttpClient.RequestOptions(
            connectTimeoutMs = 5_000L, readTimeoutMs = 5_000L, callTimeoutMs = 8_000L,
        )
        private val SEGMENT_OPTIONS = DreamHttpClient.RequestOptions(
            connectTimeoutMs = 5_000L, readTimeoutMs = 10_000L, callTimeoutMs = 15_000L,
        )

        internal fun mp4FirstPtsNanos(init: ByteArray, segment: ByteArray): Long {
            val mdhd = indexOfBox(init, "mdhd")
            val tfdt = indexOfBox(segment, "tfdt")
            if (mdhd < 0 || tfdt < 0) return -1L
            val timescale = ByteBuffer.wrap(init).getInt(mdhd + if (init[mdhd + 4].toInt() == 1) 24 else 16).toUInt().toLong()
            val times = ByteBuffer.wrap(segment)
            val decodeTime = if (segment[tfdt + 4].toInt() == 1) times.getLong(tfdt + 8) else times.getInt(tfdt + 8).toUInt().toLong()
            if (timescale == 0L) return -1L
            return decodeTime / timescale * 1_000_000_000L + decodeTime % timescale * 1_000_000_000L / timescale
        }

        private fun indexOfBox(data: ByteArray, type: String): Int =
            String(data, Charsets.ISO_8859_1).indexOf(type).takeIf { it >= 0 && it + 28 <= data.size } ?: -1

        fun supports(url: String): Boolean = url.contains(".m3u8") || url.contains(".ttvnw.net/")
    }
}
