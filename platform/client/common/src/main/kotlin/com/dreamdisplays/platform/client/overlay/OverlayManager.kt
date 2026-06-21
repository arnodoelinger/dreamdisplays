package com.dreamdisplays.platform.client.overlay

import com.dreamdisplays.api.display.model.DisplayId

/**
 * Manages overlays for displays. This is the main interface for interacting with overlays, allowing you to open, close,
 * and render them. Overlays are rendered on top of the displays.
 */
interface OverlayManager {
    /**
     * Opens a new overlay for the specified display. Returns the created overlay, or null if an overlay for that
     * display already exists.
     */
    fun openPip(displayId: DisplayId): Overlay?

    /** Closes the overlay associated with the specified display, if it exists. */
    fun closePip(displayId: DisplayId)

    /** Returns the overlay associated with the specified display, or null if no such overlay exists. */
    fun getOverlay(displayId: DisplayId): Overlay?

    /** Returns a list of all currently open overlays. */
    fun listOverlays(): List<Overlay>

    /** Renders all open overlays. This should be called during the overlay rendering phase of the render loop. */
    fun renderAll(context: OverlayRenderContext)

    /** Dispatches an event to the overlay at the specified screen coordinates. Returns true if the event was handled by an overlay. */
    fun dispatchEvent(event: OverlayEvent, atX: Float, atY: Float): Boolean

    /** Closes all open overlays. This is useful for cleanup when a display is removed or the client is shutting down. */
    fun closeAll()

    /** True when no overlays are currently open; lets render hooks skip work cheaply. */
    val isEmpty: Boolean
}
