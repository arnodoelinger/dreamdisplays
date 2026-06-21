package com.dreamdisplays.api.display.model

import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.watchparty.WatchPartySession

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

    /** The effective playback mode (`WATCH_PARTY` while a session is live, otherwise the base mode). */
    val mode: PlaybackMode = PlaybackMode.LOCAL,

    /** The live watch-party session over this display, or null when none is running. */
    val watchParty: WatchPartySession? = null,
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
