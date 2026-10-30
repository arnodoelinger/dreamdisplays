package com.dreamdisplays.api.media.source

/**
 * Metadata resolved for a media item. Fields are nullable because providers expose different facts.
 *
 * @since 1.8.x
 */
data class MediaMetadata(
    /** Human-readable title, if known. */
    val title: String?,

    /** Channel / uploader / author display name, if known. */
    val uploader: String?,

    /** Media duration in milliseconds, or null for live / unknown-length content. */
    val durationMs: Long?,

    /** Provider thumbnail URL, if available. */
    val thumbnailUrl: String?,

    /** View count, if the provider returned it. */
    val viewCount: Long?,

    /** Like count, if the provider returned it. */
    val likeCount: Long?,

    /** Provider upload date text, if available. */
    val uploadDate: String?,
) {
    companion object {
        /** Empty metadata placeholder used while resolution has no richer facts. */
        val UNKNOWN = MediaMetadata(null, null, null, null, null, null, null)
    }
}
