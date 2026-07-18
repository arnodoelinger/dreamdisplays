package com.dreamdisplays.api.media.search

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Describes a single video returned by a search or related-video query.
 *
 * @since 1.6.0
 */
@DreamDisplaysUnstableApi
data class MediaSearchResult(
    /** Result ID. */
    val id: String,

    /** Video title. */
    val title: String,

    /** Uploader. */
    val uploader: String?,

    /** Video duration. */
    val durationSec: Long?,

    /** View count of video. */
    val viewCount: Long?,

    /** Like count of video. */
    val likeCount: Long? = null,

    /** Published (date format) */
    val publishedText: String? = null,

    /** Published (days format). */
    val publishedDaysAgo: Int? = null,

    /** Overrides [getWatchUrl] for non-YouTube results (e.g. Twitch), which have no derivable watch URL. */
    val watchUrlOverride: String? = null,

    /** Overrides [getThumbnailUrl] for non-YouTube results, which have no derivable thumbnail URL. */
    val thumbnailUrlOverride: String? = null,

    /** True when this result comes from Twitch, so the UI can show a "Twitch" tag instead of/alongside "New". */
    val isTwitch: Boolean = false,

    /** The uploader / channel's avatar image URL, or null when unavailable. */
    val channelAvatarUrl: String? = null,

    /** True when the uploader/channel carries a platform "verified" badge. */
    val isVerified: Boolean = false,
) {
    /** Returns true if the video was published within the last [daysWindow] days. */
    fun isRecent(daysWindow: Int): Boolean =
        publishedDaysAgo != null && publishedDaysAgo >= 0 && publishedDaysAgo <= daysWindow

    /** Returns [watchUrlOverride] if set, else the YouTube watch URL for this video. */
    fun getWatchUrl(): String = watchUrlOverride ?: YouTubeUrls.watchUrl(id)

    /** Returns [thumbnailUrlOverride] if set, else the YouTube thumbnail URL for this video. */
    fun getThumbnailUrl(): String? = thumbnailUrlOverride ?: YouTubeUrls.thumbnailUrl(id)

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
