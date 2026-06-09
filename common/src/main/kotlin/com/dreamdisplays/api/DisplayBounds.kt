package com.dreamdisplays.api

/**
 * The bounds of a display.
 *
 * @since 1.8.0
 */
data class DisplayBounds(
    /** The [x] coordinate of the display's center, in world units. */
    val x: Double,

    /** The [y] coordinate of the display's center, in world units. */
    val y: Double,

    /** The [z] coordinate of the display's center, in world units. */
    val z: Double,

    /** The width of the display, in world units. */
    val width: Int,

    /** The height of the display, in world units. */
    val height: Int,

    /** The direction the display is facing. */
    val facing: DisplayFacing,
) {
    /** The aspect ratio of the display (width divided by height). */
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    /** Calculates the squared distance from the display's center to a point in world coordinates. */
    fun distanceSqTo(px: Double, py: Double, pz: Double): Double {
        val dx = x - px
        val dy = y - py
        val dz = z - pz
        return dx * dx + dy * dy + dz * dz
    }
}
