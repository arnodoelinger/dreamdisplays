package com.dreamdisplays.api.display.model.property

import com.dreamdisplays.api.Unstable

/**
 * The bounds of a display.
 *
 * @since 1.8.x
 */
@Unstable
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
)
