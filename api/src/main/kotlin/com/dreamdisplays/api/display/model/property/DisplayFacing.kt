package com.dreamdisplays.api.display.model.property

import com.dreamdisplays.api.Unstable
import kotlinx.serialization.Serializable

/**
 * Facing direction of a display; [byte] is stable wire encoding (not ordinal-derived).
 *
 * @since 1.0.x
 */
@Unstable
@Serializable
enum class DisplayFacing(val byte: Byte) {
    /** Facing north (−Z). */
    NORTH(0),

    /** Facing east (+X). */
    EAST(1),

    /** Facing south (+Z). */
    SOUTH(2),

    /** Facing west (−X). */
    WEST(3),

    /** Facing up (+Y); display lies flat on the floor, visible from above. */
    UP(4),

    /** Facing down (−Y); display lies flat on the ceiling, visible from below. */
    DOWN(5);

    /** The facing 180 degrees from this one. */
    val opposite: DisplayFacing
        get() = when (this) {
            NORTH -> SOUTH
            SOUTH -> NORTH
            EAST -> WEST
            WEST -> EAST
            UP -> DOWN
            DOWN -> UP
        }

    companion object {
        /** Decodes a [DisplayFacing] from its [byte] wire value; errors on an unknown byte. */
        fun fromByte(byte: Byte): DisplayFacing =
            entries.firstOrNull { it.byte == byte } ?: error("Unknown facing byte: $byte.")
    }
}
