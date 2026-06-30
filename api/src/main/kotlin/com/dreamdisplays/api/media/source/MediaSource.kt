package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.media.search.YouTubeUrls
import com.dreamdisplays.api.security.MediaHttpUrl
import java.util.Locale

/**
 * User-provided media locator before resolver-specific stream extraction.
 *
 * @since 1.8.0
 */
@DreamDisplaysUnstableApi
sealed interface MediaSource {
    /** Generic remote URL, passed through to the resolver pipeline. */
    data class Remote(val url: String) : MediaSource

    /** YouTube video identified by its 11-character id. */
    data class YouTube(val videoId: String) : MediaSource

    /** Twitch channel source. Currently not resolvable by the default pipeline. */
    data class Twitch(val channel: String) : MediaSource

    /** Direct playable stream URL. */
    data class DirectStream(val streamUrl: String) : MediaSource

    /**
     * Returns the HTTP(S) URL a resolver can feed to `yt-dlp` / `NewPipeExtractor`, or null for sources with no
     * such URL (currently [Twitch], which the resolution pipeline does not support).
     */
    fun toResolvableUrl(): String? = when (this) {
        is YouTube -> YouTubeUrls.watchUrl(videoId)
        is Remote -> url
        is DirectStream -> streamUrl
        is Twitch -> null
    }

    companion object {
        /** Parses [url] into a typed source when a known host is recognized; falls back to [Remote]. */
        fun from(url: String): MediaSource {
            YouTubeUrls.extractVideoId(url)?.let { return YouTube(it) }

            val parsed = MediaHttpUrl.parse(url) ?: MediaHttpUrl.parse("https://${url.trim()}")
            val host = parsed?.uri?.host?.lowercase(Locale.ROOT)
            if (host == "twitch.tv" || host?.endsWith(".twitch.tv") == true) {
                val channel = parsed.uri.path
                    ?.split('/')
                    ?.firstOrNull { it.isNotBlank() }
                if (!channel.isNullOrBlank()) return Twitch(channel)
            }

            return Remote(url)
        }
    }
}
