package com.dreamdisplays.platform.client.overlay

/**
 * Represents the bounds of an overlay on the display.
 */
data class OverlayBounds(
    /** The [x]-coordinate of the top-left corner of the overlay, in pixels. */
    val x: Float,

    /** The [y]-coordinate of the top-left corner of the overlay, in pixels. */
    val y: Float,

    /** The [width] of the overlay, in pixels. */
    val width: Float,

    /** The [height] of the overlay, in pixels. */
    val height: Float,

    /** The [anchor] point of the overlay. */
    val anchor: OverlayAnchor = OverlayAnchor.FREE,
) {
    /** Returns the [x]-coordinate of the right edge of the overlay, in pixels. */
    val right: Float get() = x + width

    /** Returns the [y]-coordinate of the bottom edge of the overlay, in pixels. */
    val bottom: Float get() = y + height
    // val aspectRatio: Float get() = width / height

    /** Returns true if the given point (in pixels) is within the bounds of the overlay. */
    fun contains(px: Float, py: Float): Boolean = px in x..right && py in y..bottom
    // fun movedTo(newX: Float, newY: Float): OverlayBounds = copy(x = newX, y = newY)
    // fun scaledTo(newWidth: Float, newHeight: Float): OverlayBounds = copy(width = newWidth, height = newHeight)
}
