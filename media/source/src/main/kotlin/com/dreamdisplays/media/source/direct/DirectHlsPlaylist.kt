package com.dreamdisplays.media.source.direct

import java.net.URI

/**
 * Minimal HLS playlist reader for user-pasted `.m3u8` URLs.
 *
 * Only what the resolver actually needs: split master from media playlist, read the variant ladder
 * so quality selection works on a custom HLS link the same way it does on a platform stream, and
 * tell a live playlist from a finished one. Segment parsing stays with the decoder, which reads the
 * playlist itself anyway.
 *
 * Unlike the Twitch playlists (always absolute URLs from usher), a third-party master playlist
 * usually lists its variants as relative paths, so every URI is resolved against the playlist URL.
 */
internal object DirectHlsPlaylist {
    /** One `#EXT-X-STREAM-INF` entry of a master playlist. */
    data class Variant(
        val url: String,
        val width: Int?,
        val height: Int?,
        val fps: Double?,
        val bandwidthBps: Int?,
        val codecs: String?,
    )

    /**
     * A parsed playlist.
     *
     * @property variants the master playlist's renditions; empty for a media playlist.
     * @property isLive true when the playlist can still grow — a media playlist with no
     * `#EXT-X-ENDLIST`, which is exactly the shape a live stream has.
     */
    data class Parsed(
        val variants: List<Variant>,
        val isLive: Boolean,
    ) {
        /** True when this is a master playlist, i.e. it lists renditions rather than segments. */
        val isMaster: Boolean get() = variants.isNotEmpty()
    }

    /** True when [text] looks like any HLS playlist at all. */
    fun looksLikePlaylist(text: String): Boolean = text.trimStart().startsWith("#EXTM3U")

    /**
     * Parses [text], resolving every variant URI against [baseUrl].
     *
     * A media playlist is treated as live unless it declares `#EXT-X-ENDLIST` or
     * `#EXT-X-PLAYLIST-TYPE:VOD`. A master playlist says nothing about liveness on its own, so it
     * is reported as non-live and the decoder discovers the truth from the media playlist it picks.
     */
    fun parse(text: String, baseUrl: String): Parsed {
        val variants = ArrayList<Variant>()
        var pending: Map<String, String>? = null
        var hasSegments = false
        var ended = false
        var vod = false

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXT-X-STREAM-INF:") ->
                    pending = parseAttributes(line.removePrefix("#EXT-X-STREAM-INF:"))

                line.startsWith("#EXTINF") -> hasSegments = true
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXT-X-PLAYLIST-TYPE:") ->
                    vod = line.substringAfter(':').trim().equals("VOD", ignoreCase = true)

                line.startsWith("#") -> {}

                else -> {
                    val attrs = pending ?: continue
                    pending = null
                    val resolution = attrs["RESOLUTION"]?.split('x', limit = 2)
                    variants.add(
                        Variant(
                            url = resolve(baseUrl, line),
                            width = resolution?.getOrNull(0)?.toIntOrNull(),
                            height = resolution?.getOrNull(1)?.toIntOrNull(),
                            fps = attrs["FRAME-RATE"]?.toDoubleOrNull(),
                            bandwidthBps = attrs["BANDWIDTH"]?.toIntOrNull(),
                            codecs = attrs["CODECS"]?.substringBefore(','),
                        ),
                    )
                }
            }
        }

        return Parsed(
            variants = variants.sortedByDescending { it.height ?: it.bandwidthBps ?: 0 },
            isLive = variants.isEmpty() && hasSegments && !ended && !vod,
        )
    }

    /** Resolves a possibly relative playlist [reference] against the absolute [baseUrl]. */
    private fun resolve(baseUrl: String, reference: String): String =
        runCatching { URI(baseUrl).resolve(reference).toString() }.getOrDefault(reference)

    /**
     * Splits an HLS attribute list (`KEY=VALUE,KEY="quoted,value"`) into a map, honouring quotes so
     * a comma inside `CODECS="avc1.64001f,mp4a.40.2"` does not split the attribute.
     */
    private fun parseAttributes(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val current = StringBuilder()
        var quoted = false
        val parts = ArrayList<String>()
        for (c in text) {
            when (c) {
                '"' -> quoted = !quoted
                ',' if !quoted -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        for (part in parts) {
            val key = part.substringBefore('=').trim()
            if (key.isEmpty() || !part.contains('=')) continue
            out[key] = part.substringAfter('=').trim()
        }
        return out
    }
}
