package com.dreamdisplays.api.media.search

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import java.net.URI
import java.util.Locale

/**
 * Canonical YouTube URL parsing and building: one place for video-ID extraction and the watch /
 * thumbnail URL formats. Everything that previously hand-rolled these (YtDlp, GeneralUtil, the UI's
 * hardcoded thumbnail URL) goes through here.
 *
 * @since 1.0.0
 */
@DreamDisplaysUnstableApi object YouTubeUrls {
    /** Matches a 11-character video ID. */
    private val BARE_ID = Regex("[A-Za-z0-9_-]{11}")

    /** Returns the watch-page URL for [videoId]. */
    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    /** Returns the medium-quality thumbnail URL for [videoId]. */
    fun thumbnailUrl(videoId: String): String = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    /**
     * Extracts the 11-character video ID from a full URL (`youtube.com/watch`, `youtu.be`, shorts,
     * embed, live) or a bare ID. Returns null when the input is not recognizable.
     */
    fun extractVideoId(url: String?): String? {
        if (url == null) return null
        val s = url.trim()
        if (s.isEmpty()) return null
        if (s.length == 11 && BARE_ID.matches(s)) return s
        try {
            val uri = URI.create(s)
            val host = uri.host?.lowercase(Locale.ENGLISH) ?: return null
            val path = uri.path ?: ""
            if ("youtu.be" in host) {
                val p = path.removePrefix("/")
                val slash = p.indexOf('/')
                return if (slash >= 0) p.substring(0, slash) else p
            }
            if ("youtube.com" in host) {
                uri.query?.split('&')?.forEach { part ->
                    if (part.startsWith("v=")) return part.substring(2)
                }
                if (path.startsWith("/shorts/") || path.startsWith("/embed/") || path.startsWith("/live/")) {
                    val rest = path.substring(path.indexOf('/', 1) + 1)
                    val slash = rest.indexOf('/')
                    return if (slash >= 0) rest.substring(0, slash) else rest
                }
            }
        } catch (_: Exception) {
        }
        return null
    }
}
