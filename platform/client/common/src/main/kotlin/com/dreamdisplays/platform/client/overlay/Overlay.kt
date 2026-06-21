package com.dreamdisplays.platform.client.overlay

import com.dreamdisplays.core.display.DisplayId

/**
 * Represents an overlay that can be rendered on top of a display.
 */
interface Overlay {
    /** The ID of the display this overlay is associated with. */
    val displayId: DisplayId

    /** Whether this overlay should be rendered. If false, the overlay will not be rendered but may still receive events. */
    val isVisible: Boolean

    /** The bounds of the overlay. */
    val bounds: OverlayBounds

    /** Renders the overlay. This will only be called if [isVisible] is true. */
    fun render(context: OverlayRenderContext)

    /**
     * Handles an event that occurred on the overlay. Returns true if the event was handled and should not be passed to
     * other overlays or the display itself.
     */
    fun onEvent(event: OverlayEvent): Boolean
}
