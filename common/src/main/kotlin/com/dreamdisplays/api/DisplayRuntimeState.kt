package com.dreamdisplays.api

import com.dreamdisplays.media.api.DreamMediaException

/**
 * Represents the runtime state of a display.
 *
 * @since 1.8.0
 */
sealed interface DisplayRuntimeState {
    /** The display is currently idle. */
    data object Idle : DisplayRuntimeState

    /** The display is out of range. */
    data object OutOfRange : DisplayRuntimeState

    /** The display is preparing. */
    data object Preparing : DisplayRuntimeState

    /** The display is buffering. */
    data class Buffering(val sessionId: String) : DisplayRuntimeState

    /** The display is playing. */
    data class Playing(
        val sessionId: String,
        val positionMs: Long,
        val durationMs: Long?,
    ) : DisplayRuntimeState

    /** The display is paused. */
    data class Paused(
        val sessionId: String,
        val positionMs: Long,
    ) : DisplayRuntimeState

    /** The display has failed to load. */
    data class Failed(
        val cause: DreamMediaException,
        val retryCount: Int = 0,
    ) : DisplayRuntimeState

    /** The display has been stopped. */
    data object Stopped : DisplayRuntimeState

    /** The display has been removed. */
    val isActive: Boolean
        get() = this is Playing || this is Paused || this is Buffering

    /** The session ID of the current media, if applicable. */
    val currentSessionId: String?
        get() = when (this) {
            is Playing -> sessionId
            is Paused -> sessionId
            is Buffering -> sessionId
            else -> null
        }
}
