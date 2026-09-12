package com.dreamdisplays.api.security.policy

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.source.model.MediaPlatform
import java.net.URI
import java.util.*

/**
 * Which hosts the mod talks to on its own initiative, and which it only reaches because a player
 * pasted a link at it.
 *
 * The distinction drives how a request is made rather than whether it is made: a host nobody
 * vouched for gets no identifying headers, tighter limits, and has to prove it really serves media.
 *
 * @since 1.9.x
 */
@Unstable
object MediaHosts {
    /**
     * Domains each supported platform serves from, including the CDNs its streams and thumbnails
     * live on.
     */
    private val PLATFORM_DOMAINS: List<Pair<MediaPlatform, List<String>>> = listOf(
        MediaPlatform.YOUTUBE to listOf(
            "youtube.com", "youtu.be", "youtube-nocookie.com", "ytimg.com", "ggpht.com",
            "googlevideo.com", "googleusercontent.com", "youtubei.googleapis.com",
        ),
        MediaPlatform.TWITCH to listOf("twitch.tv", "ttvnw.net", "jtvnw.net", "live-video.net"),
        MediaPlatform.VIMEO to listOf("vimeo.com", "vimeocdn.com"),
        MediaPlatform.KICK to listOf("kick.com"),
        MediaPlatform.BILIBILI to listOf("bilibili.com", "hdslb.com", "bilivideo.com"),
    )

    /** The site each platform's CDNs expect to be linked from, for the ones that check. */
    private val PLATFORM_REFERERS: Map<MediaPlatform, String> = mapOf(
        MediaPlatform.YOUTUBE to "https://www.youtube.com/",
        MediaPlatform.TWITCH to "https://www.twitch.tv/",
        MediaPlatform.VIMEO to "https://vimeo.com/",
        MediaPlatform.KICK to "https://kick.com/",
        MediaPlatform.BILIBILI to "https://www.bilibili.com/",
    )

    /** The platform [url]'s host belongs to, or null when it belongs to none of them. */
    fun platformOf(url: String): MediaPlatform? {
        val host = hostOf(url) ?: return null
        return PLATFORM_DOMAINS.firstOrNull { (_, domains) -> domains.any { covers(host, it) } }?.first
    }

    /**
     * True when [url] points at one of the supported platforms. False covers everything a player
     * can paste, which is exactly the traffic that must not carry anything identifying.
     */
    fun isFirstParty(url: String): Boolean = platformOf(url) != null

    /**
     * The `Referer` to send with [url], or null to send none.
     *
     * Some platform CDNs answer 403 without their own site here (seen on Bilibili's `bilivideo.com`
     * and Kick's thumbnails), so first-party requests carry it. A host a player supplied gets none:
     * naming a site we are not on tells its operator which mod is calling, and buys nothing.
     */
    fun refererFor(url: String): String? = platformOf(url)?.let { PLATFORM_REFERERS[it] }

    /** Host of [url] in lower case, without IPv6 literal brackets, or null when it has none. */
    fun hostOf(url: String): String? = runCatching {
        URI(url.trim()).host?.removeSurrounding("[", "]")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** True when [host] is [domain] itself or one of its subdomains. */
    private fun covers(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")
}
