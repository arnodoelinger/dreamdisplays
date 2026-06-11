package com.dreamdisplays.media.api

/** Describes a single video returned by a search or related-video query. */
data class MediaSearchResult(
    val id: String,
    val title: String,
    val uploader: String?,
    val durationSec: Long?,
    val viewCount: Long?,
    val likeCount: Long? = null,
    val publishedText: String? = null,
    val publishedDaysAgo: Int? = null,
) {
    /** Returns true if the video was published within the last [daysWindow] days. */
    fun isRecent(daysWindow: Int): Boolean =
        publishedDaysAgo != null && publishedDaysAgo >= 0 && publishedDaysAgo <= daysWindow

    /** Returns the YouTube watch URL for this video. */
    fun getWatchUrl(): String = YouTubeUrls.watchUrl(id)

    /** Returns the YouTube thumbnail URL for this video. */
    fun getThumbnailUrl(): String = YouTubeUrls.thumbnailUrl(id)

    /** Returns a formatted HH:MM:SS duration string, or empty if unavailable. */
    fun formatDuration(): String {
        val s = durationSec ?: return ""
        if (s <= 0) return ""
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%d:%02d", m, sec)
    }

    /** Returns a formatted view count string (e.g. "1.2M views"), or empty if unavailable. */
    fun formatViews(): String {
        val v = viewCount ?: return ""
        if (v <= 0) return ""
        return when {
            v >= 1_000_000_000L -> String.format("%.1fB views", v / 1_000_000_000.0)
            v >= 1_000_000L -> String.format("%.1fM views", v / 1_000_000.0)
            v >= 1_000L -> String.format("%.1fK views", v / 1_000.0)
            else -> "$v views"
        }
    }

    /** Returns a formatted like count string (e.g. "42K"), or empty if unavailable. */
    fun formatLikes(): String {
        val l = likeCount ?: return ""
        if (l <= 0) return ""
        return when {
            l >= 1_000_000_000L -> String.format("%.1fB", l / 1_000_000_000.0)
            l >= 1_000_000L -> String.format("%.1fM", l / 1_000_000.0)
            l >= 1_000L -> String.format("%.1fK", l / 1_000.0)
            else -> l.toString()
        }
    }
}
