package com.dreamdisplays.util

import com.dreamdisplays.core.display.DisplayFacing

/**
 * Facing directions for block placement and interaction. The ordinal doubles as the wire byte (see
 * [toPacket] / [fromPacket]), so the declaration order N/E/S/W is part of the protocol. Don't
 * reorder.
 */
enum class FacingUtil {
    /** Facing north (−Z). Wire byte 0. */
    NORTH,

    /** Facing east (+X). Wire byte 1. */
    EAST,

    /** Facing south (+Z). Wire byte 2. */
    SOUTH,

    /** Facing west (−X). Wire byte 3. */
    WEST,

    /** Facing up (+Y). Wire byte 4. */
    UP,

    /** Facing down (−Y). Wire byte 5. */
    DOWN;

    /** Serializes this facing to a single byte using its ordinal index. */
    fun toPacket(): Byte = ordinal.toByte()

    /** Maps this protocol facing to the API-level [DisplayFacing] with the same orientation. */
    fun toDisplayFacing(): DisplayFacing = DisplayFacing.fromByte(toPacket())

    companion object {
        /** Deserializes a facing from [data] byte; throws [IllegalArgumentException] for out-of-range values. */
        @JvmStatic fun fromPacket(data: Byte): FacingUtil {
            val values = entries
            require(data in values.indices) { "Invalid facing ID: $data" }
            return values[data.toInt()]
        }
    }
}
