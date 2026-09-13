package com.dreamdisplays.util

import com.dreamdisplays.api.display.model.property.DisplayFacing

/**
 * Facing directions for block placement and interaction. The ordinal doubles as the wire byte (see
 * [toPacket] / [fromPacket]), so the declaration order N / E / S / W is part of the protocol. Don't
 * reorder.
 */
enum class FacingUtil {
    NORTH,
    EAST,
    SOUTH,
    WEST,
    UP,
    DOWN;

    /** Serializes this facing to a single byte using its ordinal index. */
    fun toPacket(): Byte = ordinal.toByte()

    /** Maps this protocol facing to the API-level [DisplayFacing] with the same orientation. */
    fun toDisplayFacing(): DisplayFacing = DisplayFacing.fromByte(toPacket())

    companion object {
        @JvmStatic
        fun fromPacket(data: Byte): FacingUtil {
            val values = entries
            require(data in values.indices) { "Invalid facing ID: $data." }
            return values[data.toInt()]
        }
    }
}
