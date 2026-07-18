package com.dreamdisplays.media.source.twitch

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.random.Random

/**
 * Builds usher playlist URLs and parses the HLS master playlist usher returns. Twitch serves every
 * rendition as a muxed video+audio media playlist (plus one `audio_only` group), so each parsed
 * rendition maps 1:1 to one playable stream.
 */
internal object TwitchHls {
    private const val USHER_BASE = "https://usher.ttvnw.net"

    /** One `#EXT-X-STREAM-INF` variant of the master playlist. */
    data class Rendition(
        val url: String,
        val width: Int?,
        val height: Int?,
        val fps: Double?,
        val bandwidthBps: Int?,
        val codecs: String?,
        val videoGroup: String?,
    ) {
        /** True for the audio-only rendition (no RESOLUTION attribute / `audio_only` VIDEO group). */
        val isAudioOnly: Boolean get() = videoGroup == "audio_only" || (width == null && height == null)
    }

    /** The usher master-playlist URL for a live [login], signed with [token]. */
    fun liveUrl(login: String, token: TwitchAccessToken): String =
        usherUrl("$USHER_BASE/api/channel/hls/${login.lowercase(Locale.ROOT)}.m3u8", token)

    /** The usher master-playlist URL for VOD [id], signed with [token]. */
    fun vodUrl(id: String, token: TwitchAccessToken): String =
        usherUrl("$USHER_BASE/vod/$id.m3u8", token)

    /** Appends the query parameters Twitch's web player sends usher (mirrors `yt-dlp`'s set). */
    private fun usherUrl(base: String, token: TwitchAccessToken): String {
        val params = listOf(
            "allow_source" to "true",
            "allow_audio_only" to "true",
            "playlist_include_framerate" to "true",
            "player" to "twitchweb",
            // Cache-buster the web player sends; usher rejects some requests without it.
            "p" to Random.nextInt(1_000_000, 10_000_000).toString(),
            "sig" to token.signature,
            "token" to token.value,
        )
        return base + "?" + params.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
    }

    /** Parses [playlist] (an HLS master playlist) into its `#EXT-X-STREAM-INF` renditions. */
    fun parseMaster(playlist: String): List<Rendition> {
        val out = ArrayList<Rendition>()
        var pending: Map<String, String>? = null
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") ->
                    pending = parseAttributes(line.removePrefix("#EXT-X-STREAM-INF:"))

                line.isEmpty() || line.startsWith("#") -> {}

                else -> {
                    val attrs = pending ?: continue
                    pending = null
                    val resolution = attrs["RESOLUTION"]?.split('x', limit = 2)
                    out.add(
                        Rendition(
                            url = line,
                            width = resolution?.getOrNull(0)?.toIntOrNull(),
                            height = resolution?.getOrNull(1)?.toIntOrNull(),
                            fps = attrs["FRAME-RATE"]?.toDoubleOrNull(),
                            bandwidthBps = attrs["BANDWIDTH"]?.toIntOrNull(),
                            codecs = attrs["CODECS"],
                            videoGroup = attrs["VIDEO"],
                        )
                    )
                }
            }
        }
        return out
    }

    /**
     * Parses an m3u8 attribute list into a key→value map, honoring quoted values — `CODECS`
     * contains commas, so a plain split would shear it apart. Quotes are stripped from values.
     */
    private fun parseAttributes(list: String): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        var i = 0
        while (i < list.length) {
            val eq = list.indexOf('=', i)
            if (eq < 0) break
            val key = list.substring(i, eq).trim()
            var j = eq + 1
            val value: String
            if (j < list.length && list[j] == '"') {
                val close = list.indexOf('"', j + 1)
                if (close < 0) break
                value = list.substring(j + 1, close)
                j = close + 1
            } else {
                val comma = list.indexOf(',', j)
                value = (if (comma < 0) list.substring(j) else list.substring(j, comma)).trim()
                j = if (comma < 0) list.length else comma
            }
            if (key.isNotEmpty()) attrs[key] = value
            // Skip the separating comma between attributes.
            i = if (j < list.length && list[j] == ',') j + 1 else j
        }
        return attrs
    }
}
