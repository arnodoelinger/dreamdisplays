package com.dreamdisplays.player.preparation

import com.dreamdisplays.player.stream.ActiveStreams

/**
 * Result returned by [MediaPreparationService.prepare] on success.
 * Contains everything needed to start playback.
 */
internal data class PreparedMedia(
    val streamSet: ActiveStreams,
    val isLive: Boolean,
    val isSeekable: Boolean,
    val durationNanos: Long,
)
