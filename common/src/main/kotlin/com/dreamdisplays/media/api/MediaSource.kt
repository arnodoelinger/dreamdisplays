package com.dreamdisplays.media.api

sealed interface MediaSource {
    data class Remote(val url: String) : MediaSource
    data class YouTube(val videoId: String) : MediaSource
    data class Twitch(val channel: String) : MediaSource
    data class DirectStream(val streamUrl: String) : MediaSource

    companion object {
        private val YOUTUBE_ID_RE = Regex("(?:v=|youtu\\.be/|shorts/|live/)([A-Za-z0-9_-]{11})")

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
