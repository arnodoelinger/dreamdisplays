package com.dreamdisplays.platform.server.datatypes.selection

import com.dreamdisplays.api.display.model.property.DisplayRotation

/**
 * Horizontal cardinal. [PaperSelectionData] maps `BlockFace` and [VanillaSelectionData] maps `Direction` onto this so
 * the floor / ceiling rotation logic is only written once.
 */
enum class Cardinal {
    /** The four horizontal cardinal directions. */
    NORTH,
    EAST,
    SOUTH,
    WEST;

    /** Maps this cardinal to the [DisplayRotation] used to orient floor / ceiling content. */
    fun toContentRotation(): DisplayRotation = when (this) {
        NORTH -> DisplayRotation.NONE
        EAST -> DisplayRotation.RIGHT
        SOUTH -> DisplayRotation.HALF_TURN
        WEST -> DisplayRotation.LEFT
    }
}
