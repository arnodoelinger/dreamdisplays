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

    /**
     * Twitch source: a live channel, a VOD (`twitch.tv/videos/<id>`), or a clip
     * (`clips.twitch.tv/<slug>`). Exactly one of [channel] / [videoId] / [clipSlug] is set,
     * matching which of the three URL shapes [url] was.
     */
    data class Twitch(
        val url: String,
        val channel: String? = null,
        val videoId: String? = null,
        val clipSlug: String? = null,
    ) : MediaSource

    /** Direct playable stream URL. */
    data class DirectStream(val streamUrl: String) : MediaSource

    /** Returns the HTTP(S) URL a resolver can feed to `yt-dlp` / `NewPipeExtractor`. */
    fun toResolvableUrl(): String? = when (this) {
        is YouTube -> YouTubeUrls.watchUrl(videoId)
        is Remote -> url
        is DirectStream -> streamUrl
        is Twitch -> url
    }

    companion object {
        /** Parses [url] into a typed source when a known host is recognized; falls back to [Remote]. */
        fun from(url: String): MediaSource {
            YouTubeUrls.extractVideoId(url)?.let { return YouTube(it) }

            val parsed = MediaHttpUrl.parse(url) ?: MediaHttpUrl.parse("https://${url.trim()}")
            val host = parsed?.uri?.host?.lowercase(Locale.ROOT)
            if (parsed != null && (host == "twitch.tv" || host?.endsWith(".twitch.tv") == true)) {
                val segments = parsed.uri.path?.split('/')?.filter { it.isNotBlank() } ?: emptyList()
                when {
                    host == "clips.twitch.tv" && segments.isNotEmpty() ->
                        return Twitch(url, clipSlug = segments[0])

                    segments.getOrNull(0) == "videos" && segments.size > 1 ->
                        return Twitch(url, videoId = segments[1])

                    segments.isNotEmpty() -> return Twitch(url, channel = segments[0])
                }
            }

            return Remote(url)
        }
    }
}
