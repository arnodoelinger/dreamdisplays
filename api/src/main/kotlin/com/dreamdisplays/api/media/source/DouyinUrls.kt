package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.Unstable
import java.util.*

/**
 * Recognizes Douyin video pages and the `v.douyin.com` links produced by the share sheet.
 *
 * @since 1.10.3
 */
@Unstable
object DouyinUrls {
    /** Douyin aweme ids are decimal snowflakes. */
    private val VIDEO_ID = Regex("^\\d{10,24}$")

    /** Opaque token in a `v.douyin.com/<token>` share URL. */
    private val SHORT_CODE = Regex("^[A-Za-z0-9_-]{3,64}$")

    /** True when [url] points at a supported Douyin video or share link. */
    fun isDouyin(url: String): Boolean = parse(url) != null

    /** Parses [url] into a typed Douyin source without performing a network redirect. */
    fun parse(url: String): MediaSource.Douyin? {
        val parsed = MediaHttpUrl.parse(url) ?: MediaHttpUrl.parse("https://${url.trim()}") ?: return null
        val uri = parsed.uri
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val defaultPort = if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
        if (uri.rawUserInfo != null || uri.port != -1 && uri.port != defaultPort) return null
        val segments = uri.path?.trim('/')?.split('/') ?: emptyList()
        val normalized = parsed.value

        if (host == "v.douyin.com") {
            val code = segments.singleOrNull()?.takeIf { it.matches(SHORT_CODE) } ?: return null
            return MediaSource.Douyin(url = normalized, shortCode = code)
        }

        if (host != "douyin.com" && host != "www.douyin.com" && host != "iesdouyin.com" &&
            host != "www.iesdouyin.com"
        ) return null

        val idSegment = when {
            segments.size == 2 && segments[0] == "video" -> segments[1]
            segments.size == 3 && segments[0] == "share" && segments[1] == "video" -> segments[2]
            else -> null
        }
        val videoId = idSegment?.takeIf { it.matches(VIDEO_ID) } ?: return null

        // Use one extractor-supported page for canonical and legacy links, without tracking queries.
        return MediaSource.Douyin(url = "https://www.douyin.com/video/$videoId", videoId = videoId)
    }
}
