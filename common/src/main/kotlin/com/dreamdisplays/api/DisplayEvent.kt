package com.dreamdisplays.api

import com.dreamdisplays.media.api.DreamMediaException

/**
 * Represents an event that occurred on a display.
 *
 * @since 1.8.0
 */
sealed interface DisplayEvent {
    /** The ID of the display that the event occurred on. */
    val displayId: DisplayId

    /** Created when a display is created. */
    data class Created(override val displayId: DisplayId, val display: Display) : DisplayEvent

    /** Created when a display is removed. */
    data class Removed(override val displayId: DisplayId) : DisplayEvent

    /** Signifies that the display's settings have been changed. */
    data class SettingsChanged(
        override val displayId: DisplayId,
        val previous: DisplaySettings,
        val current: DisplaySettings,
    ) : DisplayEvent

    /** Signifies that the display's state has changed. */
    data class StateChanged(
        override val displayId: DisplayId,
        val previous: DisplayRuntimeState,
        val current: DisplayRuntimeState,
    ) : DisplayEvent

    /** Signifies that the display's URL has changed. */
    data class UrlChanged(override val displayId: DisplayId, val url: String?) : DisplayEvent

    /** Signifies that the display's media has encountered an error. */
    data class MediaError(override val displayId: DisplayId, val cause: DreamMediaException) : DisplayEvent

    /** Signifies that the display's media has been loaded and is now within range. */
    data class LoadedIntoRange(override val displayId: DisplayId) : DisplayEvent

    /** Signifies that the display's media has been unloaded and is now out of range. */
    data class UnloadedOutOfRange(override val displayId: DisplayId) : DisplayEvent

    // TODO: add more events like quality change, render distance change, etc.
}
