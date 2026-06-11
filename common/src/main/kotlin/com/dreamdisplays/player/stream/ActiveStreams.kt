package com.dreamdisplays.player.stream

import com.dreamdisplays.media.api.MediaStream

/**
 * All currently-selected and available streams for one playback session.
 * Replaced atomically on quality change or re-initialization; never partially updated.
 */
internal data class ActiveStreams(
    val availableVideo: List<MediaStream>,
    val availableAudio: List<MediaStream>,
    val currentVideo: MediaStream,
    val currentAudio: MediaStream,
)
