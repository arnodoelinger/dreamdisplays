package com.dreamdisplays.media.source.direct

import java.net.URI

/**
 * Minimal HLS playlist reader for user-pasted `.m3u8` URLs. Only what the resolver actually needs: split master / media
 * playlists, variant and audio-rendition parsing.
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
        /** The `AUDIO` group this variant takes its sound from, or null when the audio is muxed in. */
        val audioGroupId: String?,
    )

    /**
     * One `#EXT-X-MEDIA:TYPE=AUDIO` rendition that lives in its own playlist.
     * A master playlist that declares this must be fetched separately from its video variant.
     */
    data class AudioRendition(
        val url: String,
        val groupId: String,
        val name: String?,
        val language: String?,
        val isDefault: Boolean,
    )

    /** A parsed playlist. [isLive] is true when it has no `#EXT-X-ENDLIST` tag, which is exactly the shape a live stream has. */
    data class Parsed(
        val variants: List<Variant>,
        val audioRenditions: List<AudioRendition>,
        val isLive: Boolean,
        val hasInitSegment: Boolean = false,
        val totalDurationNanos: Long = 0L,
    ) {
        /** True when this is a master playlist, i.e. it lists renditions rather than segments. */
        val isMaster: Boolean get() = variants.isNotEmpty()

        /** Separate audio playlists usable by [variant], best (default) first. */
        fun audioFor(variant: Variant): List<AudioRendition> {
            val group = variant.audioGroupId ?: return emptyList()
            return audioRenditions.filter { it.groupId == group }.sortedByDescending { it.isDefault }
        }
    }

    /** True when [text] looks like any HLS playlist at all. */
    fun looksLikePlaylist(text: String): Boolean = text.trimStart().startsWith("#EXTM3U")

    /**
     * Parses [text], resolving every variant URI against [baseUrl].
     * A media playlist is treated as live unless it declares `#EXT-X-ENDLIST`.
     */
    fun parse(text: String, baseUrl: String): Parsed {
        val variants = ArrayList<Variant>()
        val audio = ArrayList<AudioRendition>()
        val seenVariantUrls = HashSet<String>()
        var pending: Map<String, String>? = null
        var hasSegments = false
        var ended = false
        var vod = false
        var hasInit = false
        var segmentNanos = 0L

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXT-X-STREAM-INF:") ->
                    pending = parseAttributes(line.removePrefix("#EXT-X-STREAM-INF:"))

                line.startsWith("#EXT-X-MEDIA:") ->
                    parseAudioRendition(parseAttributes(line.removePrefix("#EXT-X-MEDIA:")), baseUrl)
                        ?.let(audio::add)

                line.startsWith("#EXT-X-MAP") -> hasInit = true
                line.startsWith("#EXTINF") -> {
                    hasSegments = true
                    segmentNanos += extInfNanos(line)
                }

                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXT-X-PLAYLIST-TYPE:") ->
                    vod = line.substringAfter(':').trim().equals("VOD", ignoreCase = true)

                line.startsWith("#") -> {}

                else -> {
                    val attrs = pending ?: continue
                    pending = null
                    val url = resolve(baseUrl, line)
                    // The same video rendition is listed once per audio group it can pair with
                    // (Apple's reference master lists every variant three times, for stereo / AC-3 /
                    // Dolby). They are one entry in the quality ladder, not three.
                    if (!seenVariantUrls.add(url)) continue
                    val resolution = attrs["RESOLUTION"]?.split('x', limit = 2)
                    variants.add(
                        Variant(
                            url = url,
                            width = resolution?.getOrNull(0)?.toIntOrNull(),
                            height = resolution?.getOrNull(1)?.toIntOrNull(),
                            fps = attrs["FRAME-RATE"]?.toDoubleOrNull(),
                            bandwidthBps = attrs["BANDWIDTH"]?.toIntOrNull(),
                            codecs = attrs["CODECS"]?.substringBefore(','),
                            audioGroupId = attrs["AUDIO"]?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }

        return Parsed(
            variants = variants.sortedByDescending { it.height ?: it.bandwidthBps ?: 0 },
            audioRenditions = audio,
            isLive = variants.isEmpty() && hasSegments && !ended && !vod,
            hasInitSegment = hasInit,
            totalDurationNanos = segmentNanos,
        )
    }

    /** Content duration of an `#EXTINF:<seconds>,<title>` line; 0 when it cannot be read. */
    private fun extInfNanos(line: String): Long {
        val seconds = line.substringAfter(':', "").substringBefore(',').trim().toDoubleOrNull() ?: return 0L
        if (!seconds.isFinite() || seconds <= 0.0) return 0L
        return (seconds * 1_000_000_000.0).toLong()
    }

    /**
     * Reads one `#EXT-X-MEDIA` tag, keeping only audio renditions that live in their own playlist.
     * A `TYPE=AUDIO` tag without a `URI` means the audio is already inside the variants, and a
     * subtitle / closed-caption tag is not something a display can play.
     */
    private fun parseAudioRendition(attrs: Map<String, String>, baseUrl: String): AudioRendition? {
        if (!attrs["TYPE"].equals("AUDIO", ignoreCase = true)) return null
        val uri = attrs["URI"]?.takeIf { it.isNotBlank() } ?: return null
        val group = attrs["GROUP-ID"]?.takeIf { it.isNotBlank() } ?: return null
        return AudioRendition(
            url = resolve(baseUrl, uri),
            groupId = group,
            name = attrs["NAME"]?.takeIf { it.isNotBlank() },
            language = attrs["LANGUAGE"]?.takeIf { it.isNotBlank() },
            isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true),
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
