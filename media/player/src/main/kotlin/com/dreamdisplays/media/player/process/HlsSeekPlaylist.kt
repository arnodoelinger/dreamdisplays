package com.dreamdisplays.media.player.process

import com.dreamdisplays.util.net.DreamHttpClient
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.Base64
import java.util.Collections

/**
 * Seeks a fragmented-MP4 HLS rendition by handing `FFmpeg` a playlist that starts where the seek
 * does, instead of asking its demuxer to jump.
 */
internal object HlsSeekPlaylist {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** A playlist larger than this is not worth reading; real ones are a few tens of kilobytes. */
    private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024

    /**
     * Ceiling on the generated URI. Command lines are not unbounded, and a playlist long enough to
     * approach this (tens of thousands of segments) is better served by the slow path than by a
     * process that fails to start.
     */
    private const val MAX_URI_LENGTH = 192 * 1024

    /** How long a fetched playlist body stays reusable — long enough to cover a scrub. Not session. */
    private const val CACHE_TTL_NANOS = 30L * 1_000_000_000L

    /** Bounded cache of playlist bodies, so scrubbing does not re-fetch the same playlist per seek. */
    private const val CACHE_LIMIT = 8

    /** Tags that describe the playlist as a whole and must survive the trim. */
    private val HEADER_TAGS = listOf(
        "#EXTM3U", "#EXT-X-VERSION", "#EXT-X-TARGETDURATION", "#EXT-X-PLAYLIST-TYPE",
        "#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-MAP",
    )

    /** Matches the `URI="..."` attribute of `#EXT-X-MAP`, the one reference in a header tag. */
    private val URI_ATTRIBUTE = Regex("""URI="([^"]*)"""")

    /**
     * A playlist trimmed to a seek target: [url] to feed the process, plus [residualNanos] left over for the caller to
     * discard on the output side, which is cheap over one segment's worth of data.
     */
    class Trimmed(@JvmField val url: String, @JvmField val residualNanos: Long)

    private class CachedBody(@JvmField val text: String, @JvmField val fetchedNanos: Long)

    private val cache: MutableMap<String, CachedBody> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedBody>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CachedBody>): Boolean = size > CACHE_LIMIT
        },
    )

    /**
     * Builds a playlist that begins at [offsetNanos] within [playlistUrl], or null when that cannot
     * be done — not an fMP4 playlist, unreadable, past the end, or too large to inline. Every null
     * is a fall-back to the caller's ordinary seek, never a failure.
     */
    fun trim(playlistUrl: String, offsetNanos: Long): Trimmed? {
        if (offsetNanos <= 0L) return null
        return trimText(body(playlistUrl) ?: return null, playlistUrl, offsetNanos)
    }

    /** The pure half of [trim]: everything but the fetch, so it can be exercised without a network. */
    fun trimText(text: String, playlistUrl: String, offsetNanos: Long): Trimmed? {
        if (offsetNanos <= 0L) return null
        if (!text.contains("#EXT-X-MAP")) return null // Only fMP4 needs this; TS seeks correctly

        val header = StringBuilder()
        val kept = StringBuilder()
        var elapsedNanos = 0L
        var residualNanos = 0L
        var started = false
        var segments = 0
        var pending: MutableList<String>? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF") -> pending = mutableListOf(line)
                line.startsWith("#EXT-X-BYTERANGE") -> pending?.add(line)
                line.startsWith("#EXT-X-ENDLIST") -> kept.append(line).append('\n')
                line.startsWith("#") -> headerLine(line, playlistUrl)?.let { header.append(it).append('\n') }

                else -> {
                    val tags = pending ?: continue
                    pending = null
                    val durationNanos = extInfNanos(tags[0]) ?: continue
                    if (!started && elapsedNanos + durationNanos > offsetNanos) {
                        started = true
                        residualNanos = offsetNanos - elapsedNanos
                    }
                    if (started) {
                        tags.forEach { kept.append(it).append('\n') }
                        kept.append(absolute(playlistUrl, line)).append('\n')
                        segments++
                    }
                    elapsedNanos += durationNanos
                }
            }
        }

        if (!started || segments == 0) return null // The target is past the end of the playlist
        val uri = "data:application/vnd.apple.mpegurl;base64," +
                Base64.getEncoder().encodeToString((header.toString() + kept).toByteArray())
        if (uri.length > MAX_URI_LENGTH) {
            logger.debug("Trimmed playlist for {} is {} chars; too long to inline.", playlistUrl.take(80), uri.length)
            return null
        }
        logger.debug(
            "Seeking {} to {} ms via a {}-segment trimmed playlist (+{} ms into the first one).",
            playlistUrl.take(80), offsetNanos / 1_000_000, segments, residualNanos / 1_000_000,
        )
        return Trimmed(uri, residualNanos)
    }

    /** Keeps a header tag, rewriting `#EXT-X-MAP`'s URI absolute; null for tags that must be dropped. */
    private fun headerLine(line: String, baseUrl: String): String? {
        if (HEADER_TAGS.none { line == it || line.startsWith("$it:") }) return null
        if (!line.startsWith("#EXT-X-MAP")) return line
        return URI_ATTRIBUTE.replace(line) { m -> """URI="${absolute(baseUrl, m.groupValues[1])}"""" }
    }

    /** Content duration of an `#EXTINF:<seconds>,<title>` line, or null when it is malformed. */
    private fun extInfNanos(line: String): Long? {
        val seconds = line.substringAfter(':', "").substringBefore(',').trim().toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds <= 0.0) return null
        return (seconds * 1_000_000_000.0).toLong()
    }

    /** Resolves a possibly relative playlist [reference] against [baseUrl]. */
    private fun absolute(baseUrl: String, reference: String): String =
        runCatching { URI(baseUrl).resolve(reference).toString() }.getOrDefault(reference)

    /** Returns [url]'s body, from the short-lived cache when a recent copy is available. */
    private fun body(url: String): String? {
        val now = System.nanoTime()
        cache[url]?.let { if (now - it.fetchedNanos < CACHE_TTL_NANOS) return it.text }
        val text = runCatching {
            DreamHttpClient.executeLimited(
                url,
                maxBytes = MAX_PLAYLIST_BYTES,
                options = DreamHttpClient.RequestOptions(readTimeoutMs = 10_000L, callTimeoutMs = 12_000L),
            ).bodyString()
        }.getOrElse {
            logger.debug("Could not read {} for a trimmed seek: {}.", url.take(80), it.message)
            return null
        }
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        cache[url] = CachedBody(text, now)
        return text
    }
}
