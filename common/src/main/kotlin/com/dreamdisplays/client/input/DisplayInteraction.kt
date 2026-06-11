package com.dreamdisplays.client.input

import com.dreamdisplays.api.DisplayId

/**
 * Represents an interaction with a display, such as right-clicking or looking at it. Used for input handling and
 * event dispatching.
 *
 * @since 1.0.0
 */
sealed interface DisplayInteraction {
    /** Represents a right-click interaction with a display. */
    data class RightClicked(val displayId: DisplayId) : DisplayInteraction

    /** Represents a left-click interaction with a display. */
    data class Looked(val displayId: DisplayId) : DisplayInteraction

    /** Represents a left-click release interaction with a display. */
    data class LookedAway(val displayId: DisplayId) : DisplayInteraction
}
