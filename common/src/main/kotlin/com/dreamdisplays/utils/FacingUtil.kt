package com.dreamdisplays.utils

/** Facing directions for block placement and interaction. */
enum class FacingUtil {
    NORTH, EAST, SOUTH, WEST;

    fun toPacket(): Byte = ordinal.toByte()

    companion object {
        @JvmStatic fun fromPacket(data: Byte): FacingUtil {
            val values = entries
            require(data in values.indices) { "Invalid facing ID: $data" }
            return values[data.toInt()]
        }
    }
}
