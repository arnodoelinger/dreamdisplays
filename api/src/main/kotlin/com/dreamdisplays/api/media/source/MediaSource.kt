package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/** User-provided media locator before resolver-specific stream extraction. */
@DreamDisplaysUnstableApi sealed interface MediaSource {
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
        is YouTube -> "https://www.youtube.com/watch?v=$videoId"
        is Remote -> url
        is DirectStream -> streamUrl
        is Twitch -> null
    }

    companion object {
        private val YOUTUBE_ID_RE = Regex("(?:v=|youtu\\.be/|shorts/|live/)([A-Za-z0-9_-]{11})")

        /** Parses [url] into a typed source when a known host is recognized; falls back to [Remote]. */
        fun from(url: String): MediaSource = when {
            "youtube.com" in url || "youtu.be" in url -> {
                val id = YOUTUBE_ID_RE.find(url)?.groupValues?.get(1)
                if (id != null) YouTube(id) else Remote(url)
            }
            "twitch.tv" in url -> Twitch(url.substringAfterLast("/"))
            else -> Remote(url)
        }
    }
}
