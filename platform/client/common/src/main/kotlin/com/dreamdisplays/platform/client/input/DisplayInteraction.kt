package com.dreamdisplays.platform.client.input

import com.dreamdisplays.api.display.model.DisplayId

/**
 * Represents an interaction with a display, such as right-clicking or looking at it. Used for input handling and
 * event dispatching.
 */
sealed interface DisplayInteraction {
    /** Represents a right-click interaction with a display. */
    data class RightClicked(val displayId: DisplayId) : DisplayInteraction

    /** Represents the player starting to look at a display. */
    data class Looked(val displayId: DisplayId) : DisplayInteraction

    /** Represents the player looking away from a display. */
    data class LookedAway(val displayId: DisplayId) : DisplayInteraction
}
