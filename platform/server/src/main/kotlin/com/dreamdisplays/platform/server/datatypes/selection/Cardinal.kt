package com.dreamdisplays.platform.server.datatypes.selection

import com.dreamdisplays.api.display.model.ContentRotation

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

    /** Maps this cardinal to the [ContentRotation] used to orient floor / ceiling content. */
    fun toContentRotation(): ContentRotation = when (this) {
        NORTH -> ContentRotation.NONE
        EAST -> ContentRotation.RIGHT
        SOUTH -> ContentRotation.HALF_TURN
        WEST -> ContentRotation.LEFT
    }
}
