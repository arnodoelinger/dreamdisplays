package com.dreamdisplays.media.player.preparation

import com.dreamdisplays.media.player.stream.ActiveStreams

/**
 * Result returned by [MediaPreparationService.prepare] on success.
 * Contains everything needed to start playback.
 */
data class PreparedMedia(
    val streamSet: ActiveStreams,
    val isLive: Boolean,
    val isSeekable: Boolean,
    val durationNanos: Long,
)
