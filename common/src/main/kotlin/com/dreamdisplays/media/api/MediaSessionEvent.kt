@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.media.api

import com.dreamdisplays.api.DreamDisplaysUnstableApi

import kotlin.time.Duration

sealed interface MediaSessionEvent {
    data class StateChanged(
        val previous: MediaSessionState,
        val current: MediaSessionState,
    ) : MediaSessionEvent

    data class PositionChanged(val position: Duration) : MediaSessionEvent
    data class Error(val cause: DreamMediaException) : MediaSessionEvent
    data object Ended : MediaSessionEvent
    data object Stalled : MediaSessionEvent
    data object Recovered : MediaSessionEvent
    data class MetadataReady(val metadata: MediaMetadata) : MediaSessionEvent
}
