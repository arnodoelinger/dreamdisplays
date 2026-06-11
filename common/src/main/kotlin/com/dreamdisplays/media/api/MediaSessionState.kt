package com.dreamdisplays.media.api

sealed interface MediaSessionState {
    data object Idle : MediaSessionState
    data object Preparing : MediaSessionState
    data class Active(val isPlaying: Boolean, val isBuffering: Boolean) : MediaSessionState
    data class Stalled(val retryCount: Int) : MediaSessionState
    data class Error(val cause: DreamMediaException) : MediaSessionState
    data object Ended : MediaSessionState
    data object Released : MediaSessionState

    val isTerminal: Boolean get() = this is Released || (this is Error && cause.isFatal)
}
