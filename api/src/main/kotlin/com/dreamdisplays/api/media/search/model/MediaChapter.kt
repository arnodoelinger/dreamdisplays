package com.dreamdisplays.api.media.search.model

import com.dreamdisplays.api.Unstable

/**
 * A single chapter marker within a video. Only YouTube publishes these today.
 *
 * @since 1.9.x
 */
@Unstable
data class MediaChapter(
    /** Chapter title, as shown under the video. */
    val title: String,

    /** Offset from the start of the video, in seconds. */
    val startSeconds: Long,
)
