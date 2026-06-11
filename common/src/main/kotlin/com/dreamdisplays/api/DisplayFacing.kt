package com.dreamdisplays.api

/**
 * The facing direction of a display. [byte] is the stable wire encoding (see [fromByte]); it is
 * fixed per constant rather than derived from the ordinal, so constants may be reordered safely.
 *
 * @property byte the stable serialized value for this facing.
 *
 * @since 1.0.0
 */
enum class DisplayFacing(val byte: Byte) {
    /** Facing north (−Z). */
    NORTH(0),

    /** Facing east (+X). */
    EAST(1),

    /** Facing south (+Z). */
    SOUTH(2),

    /** Facing west (−X). */
    WEST(3);

    /** The facing 180 degrees from this one. */
    val opposite: DisplayFacing get() = when (this) {
        NORTH -> SOUTH
        SOUTH -> NORTH
        EAST -> WEST
        WEST -> EAST
    }

    companion object {
        /** Decodes a [DisplayFacing] from its [byte] wire value; errors on an unknown byte. */
        fun fromByte(byte: Byte): DisplayFacing =
            entries.firstOrNull { it.byte == byte } ?: error("Unknown facing byte: $byte.")
    }
}
