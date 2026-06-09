package com.dreamdisplays.client.overlay

/**
 * Context for rendering an overlay, providing information about the screen dimensions, scale factor, and partial tick time.
 *
 * @since 1.7.0
 */
interface OverlayRenderContext {
    /** The width of the screen in pixels. */
    val screenWidth: Int

    /** The height of the screen in pixels. */
    val screenHeight: Int

    /** The scale factor for rendering the overlay, which may be used to adjust the size of elements based on the display's DPI or user settings. */
    val scaleFactor: Double

    /**
     * The partial tick time, which can be used for smooth animations by providing the fraction of a tick that has passed
     * since the last full tick. This value is typically between 0.0 and 1.0.
     */
    val partialTick: Float
}
