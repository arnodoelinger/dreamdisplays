package com.dreamdisplays.media.source.platform

import com.dreamdisplays.api.media.source.MediaMetadata
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Lightweight, provider-agnostic video metadata: the facts the menu needs to draw a card and the
 * preview overlay before the stream is fully resolved (or without ever resolving it, for a card
 * that is only shown, never played).
 *
 * This is the shared shape Vimeo and Kick both populate, so the UI reads one type instead of a
 * bespoke class per platform (the way Twitch predates this and still has its own).
 *
 * @since 1.9.0
 */
data class PlatformVideoMetadata(
    val title: String?,
    val uploader: String?,
    val thumbnailUrl: String?,
    val uploaderAvatarUrl: String? = null,
    val viewCount: Long? = null,
    val durationSec: Long? = null,
    val isLive: Boolean = false,
) {
    /** Converts to the resolver-facing [MediaMetadata], carrying [duration] through unchanged. */
    fun toMediaMetadata(duration: Duration? = durationSec?.takeIf { it > 0 }?.seconds): MediaMetadata =
        MediaMetadata(
            title = title,
            uploader = uploader,
            duration = duration,
            thumbnailUrl = thumbnailUrl,
            viewCount = viewCount,
            likeCount = null,
            uploadDate = null,
        )
}
