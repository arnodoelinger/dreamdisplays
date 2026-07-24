package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.security.MediaHttpUrl
import java.util.*

/**
 * Recognizes and dissects Vimeo URLs, in every shape players actually paste.
 *
 * A Vimeo link is a numeric video id, sometimes followed by an unlisted-video hash that the
 * player config endpoint needs to authorize playback. Both are pulled out here so [MediaSource]
 * stays declarative and the parsing has its own tests.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
object VimeoUrls {
    /** Path segments that are grouping keywords, never the video id itself. */
    private val SKIP_SEGMENTS = setOf(
        "video", "channels", "groups", "album", "ondemand", "showcase", "user", "manage", "categories",
    )

    /** A raw numeric video id. */
    private val VIDEO_ID = Regex("^\\d{6,12}$")

    /** An unlisted-video hash: the short hex token that follows the id on a private link. */
    private val HASH = Regex("^[0-9a-fA-F]{6,16}$")

    /** True when [url] points at a Vimeo video (`vimeo.com` or `player.vimeo.com`). */
    fun isVimeo(url: String): Boolean = parse(url) != null

    /**
     * Parses [url] into its `(videoId, hash?)`, or null when it is not a recognizable Vimeo video
     * link. The hash is only reported when it directly follows the id, matching how Vimeo signs
     * unlisted videos (`vimeo.com/<id>/<hash>`).
     */
    fun parse(url: String): MediaSource.Vimeo? {
        val parsed = MediaHttpUrl.parse(url) ?: MediaHttpUrl.parse("https://${url.trim()}") ?: return null
        val host = parsed.uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        if (host != "vimeo.com" && host != "player.vimeo.com") return null

        val segments = parsed.uri.path?.split('/')?.filter { it.isNotBlank() } ?: return null
        val idIndex = segments.indexOfFirst { it.matches(VIDEO_ID) && it !in SKIP_SEGMENTS }
        if (idIndex < 0) return null
        val videoId = segments[idIndex]

        // The hash is the segment right after the id, when it looks like a hash and is not another
        // keyword (so "vimeo.com/123/settings" never mistakes "settings" for a hash).
        val hash = segments.getOrNull(idIndex + 1)
            ?.takeIf { it.matches(HASH) && it !in SKIP_SEGMENTS }
            ?: parsed.uri.query
                ?.split('&')
                ?.firstOrNull { it.startsWith("h=") }
                ?.substringAfter('=')
                ?.takeIf { it.matches(HASH) }

        // Store the scheme-normalized URL (parsed.value), never the raw paste: a scheme-less
        // "vimeo.com/123" would otherwise be rejected by the server's http(s)-only URL policy.
        return MediaSource.Vimeo(url = parsed.value, videoId = videoId, hash = hash)
    }
}
