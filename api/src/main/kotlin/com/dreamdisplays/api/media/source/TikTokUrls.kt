package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.Unstable
import java.util.*

/**
 * Recognizes TikTok video, short-link, embed, and live URLs supported by `yt-dlp`.
 *
 * @since 1.10.3
 */
@Unstable
object TikTokUrls {
    /** TikTok post and room ids are decimal snowflakes. */
    private val NUMERIC_ID = Regex("^\\d{10,24}$")

    /** Usernames accepted by TikTok's canonical `@user` URL form. */
    private val USERNAME = Regex("^[A-Za-z0-9._-]+$")

    /** Short-link tokens used by `vm.tiktok.com`, `vt.tiktok.com`, and `/t/<token>`. */
    private val SHORT_CODE = Regex("^[A-Za-z0-9_-]{3,64}$")

    /** True when [url] is a supported TikTok video or live URL. */
    fun isTikTok(url: String): Boolean = parse(url) != null

    /**
     * Parses [url] without following redirects. A short link therefore has no [MediaSource.TikTok.videoId]
     * until `yt-dlp` resolves it, but remains a first-class TikTok source throughout the client.
     */
    fun parse(url: String): MediaSource.TikTok? {
        val parsed = MediaHttpUrl.parse(url) ?: MediaHttpUrl.parse("https://${url.trim()}") ?: return null
        val uri = parsed.uri
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val defaultPort = if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
        if (uri.rawUserInfo != null || uri.port != -1 && uri.port != defaultPort) return null
        val segments = uri.path?.trim('/')?.split('/') ?: emptyList()
        val normalized = parsed.value

        if (host == "vm.tiktok.com" || host == "vt.tiktok.com") {
            val code = segments.singleOrNull()?.takeIf { it.matches(SHORT_CODE) } ?: return null
            return MediaSource.TikTok(url = normalized, shortCode = code)
        }

        if (host != "tiktok.com" && host != "www.tiktok.com" && host != "m.tiktok.com" &&
            host != "tiktokv.com" && host != "www.tiktokv.com"
        ) return null

        if (segments.getOrNull(0) == "t") {
            if (segments.size != 2) return null
            val code = segments.getOrNull(1)?.takeIf { it.matches(SHORT_CODE) } ?: return null
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return MediaSource.TikTok(url = "https://www.tiktok.com/t/$code$query", shortCode = code)
        }

        if (segments.getOrNull(0) == "embed") {
            val idSegment = when {
                segments.size == 2 -> segments[1]
                segments.size == 3 && segments[1] == "v2" -> segments[2]
                else -> return null
            }
            val videoId = idSegment.takeIf { it.matches(NUMERIC_ID) } ?: return null
            return videoSource(videoId)
        }

        if (segments.getOrNull(0) == "share" && segments.getOrNull(1) == "video") {
            if (segments.size != 3) return null
            val videoId = segments.getOrNull(2)?.takeIf { it.matches(NUMERIC_ID) } ?: return null
            return videoSource(videoId)
        }

        if (host == "m.tiktok.com" && segments.getOrNull(0) == "share" && segments.getOrNull(1) == "live") {
            if (segments.size != 3) return null
            val roomId = segments.getOrNull(2)?.takeIf { it.matches(NUMERIC_ID) } ?: return null
            return MediaSource.TikTok(url = "https://m.tiktok.com/share/live/$roomId", liveRoomId = roomId)
        }

        val userSegment = segments.getOrNull(0)?.takeIf { it.startsWith('@') } ?: return null
        val username = userSegment.removePrefix("@").takeIf { it.matches(USERNAME) }
        if (username == null && userSegment != "@") return null
        return when {
            segments.getOrNull(1) == "video" && segments.size == 3 -> {
                val videoId = segments.getOrNull(2)?.takeIf { it.matches(NUMERIC_ID) } ?: return null
                videoSource(videoId, username)
            }

            segments.getOrNull(1) == "live" && segments.size == 2 && username != null ->
                MediaSource.TikTok(url = "https://www.tiktok.com/@$username/live", username = username, isLive = true)

            else -> null
        }
    }

    private fun videoSource(videoId: String, username: String? = null): MediaSource.TikTok =
        MediaSource.TikTok(
            url = "https://www.tiktok.com/@${username ?: "_"}/video/$videoId",
            videoId = videoId,
            username = username,
        )
}
