@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.api

/**
 * Represents a display that can be rendered on the client.
 *
 * @since 1.0.0
 */
data class Display(
    /** The unique identifier of the display. */
    val id: DisplayId,

    /** The bounds of the display. */
    val bounds: DisplayBounds,

    /** The settings for the display. */
    val settings: DisplaySettings,

    /** The URL of the video to display. */
    val url: String?,

    /** The current runtime state of the display. */
    val state: DisplayRuntimeState,
) {
    /** Returns true if the display is currently playing. */
    val isPlaying: Boolean get() = state is DisplayRuntimeState.Playing

    /** Returns true if the display is currently paused. */
    val isPaused: Boolean get() = state is DisplayRuntimeState.Paused

    /** Returns true if the display is currently idle. */
    val isIdle: Boolean get() = state is DisplayRuntimeState.Idle

    /** Returns true if the display has a URL set. */
    val hasUrl: Boolean get() = !url.isNullOrBlank()
}
