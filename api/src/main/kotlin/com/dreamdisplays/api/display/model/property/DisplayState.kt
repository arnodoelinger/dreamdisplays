package com.dreamdisplays.api.display.model.property

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.model.DreamMediaException

/**
 * Represents the runtime state of a display.
 *
 * @since 1.8.x
 */
@Unstable
sealed interface DisplayState {
    /** The display is currently idle. */
    data object Idle : DisplayState

    /** The display is out of range. */
    data object OutOfRange : DisplayState

    /** The display is preparing. */
    data object Preparing : DisplayState

    /** The display is buffering. */
    data class Buffering(val sessionId: String) : DisplayState

    /** The display is playing. */
    data class Playing(
        val sessionId: String,
        val positionMs: Long,
        val durationMs: Long?,
    ) : DisplayState

    /** The display is paused. */
    data class Paused(
        val sessionId: String,
        val positionMs: Long,
    ) : DisplayState

    /** The display has failed to load. */
    data class Failed(
        val cause: DreamMediaException,
        val retryCount: Int = 0,
    ) : DisplayState

    /** The display has been stopped. */
    data object Stopped : DisplayState
}
