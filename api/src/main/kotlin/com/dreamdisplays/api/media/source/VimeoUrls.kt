package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.Experimental
import java.util.*

/**
 * Recognizes and dissects Vimeo URLs, extracting video ID and optional unlisted-video hash.
 *
 * @since 1.9.x
 */
@Experimental
object VimeoUrls {
    private val SKIP_SEGMENTS = setOf(
        "video",
        "channels",
        "groups",
        "album",
        "ondemand",
        "showcase",
        "user",
        "manage",
        "categories",
    )

    private val VIDEO_ID = Regex("^\\d{6,12}$")
    private val HASH = Regex("^[0-9a-fA-F]{6,16}$")

    /** True when [url] points at a Vimeo video (`vimeo.com` or `player.vimeo.com`). */
    fun isVimeo(url: String): Boolean = parse(url) != null

    /** Parses [url] into `(videoId, hash?)` or `null` if unrecognizable; hash must directly follow ID. */
    fun parse(url: String): MediaSource.Vimeo? {
        val parsed = MediaHttpUrl.parse(url) ?: MediaHttpUrl.parse("https://${url.trim()}") ?: return null
        val host = parsed.uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        if (host != "vimeo.com" && host != "player.vimeo.com") return null

        val segments = parsed.uri.path?.split('/')?.filter { it.isNotBlank() } ?: return null
        val idIndex = segments.indexOfFirst { it.matches(VIDEO_ID) && it !in SKIP_SEGMENTS }
        if (idIndex < 0) return null
        val videoId = segments[idIndex]

        val hash = segments.getOrNull(idIndex + 1)
            ?.takeIf { it.matches(HASH) && it !in SKIP_SEGMENTS }
            ?: parsed.uri.query
                ?.split('&')
                ?.firstOrNull { it.startsWith("h=") }
                ?.substringAfter('=')
                ?.takeIf { it.matches(HASH) }

        return MediaSource.Vimeo(url = parsed.value, videoId = videoId, hash = hash)
    }
}
