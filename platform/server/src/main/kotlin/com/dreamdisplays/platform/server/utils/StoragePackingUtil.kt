package com.dreamdisplays.platform.server.utils

import com.dreamdisplays.api.display.model.ContentRotation
import net.minecraft.core.Direction
import java.nio.ByteBuffer
import java.util.*

/**
 * Binary packing helpers for the compact SQL row format shared by every storage backend: facing +
 * rotation into one column int, a 3D block position into one long, two 16-bit dimensions into one
 * long, and `UUID` <-> `ByteArray` conversion for the primary key columns.
 */
object StoragePackingUtil {
    /** Directions and content rotations are packed into a single column. */
    val DIRECTION_TO_ORDINAL = mapOf(
        Direction.NORTH to 0, Direction.EAST to 1, Direction.SOUTH to 2, Direction.WEST to 3,
        Direction.UP to 4, Direction.DOWN to 5,
    )

    /** Inverse mapping of [DIRECTION_TO_ORDINAL]. */
    val ORDINAL_TO_DIRECTION = mapOf(
        0 to Direction.NORTH, 1 to Direction.EAST, 2 to Direction.SOUTH, 3 to Direction.WEST,
        4 to Direction.UP, 5 to Direction.DOWN,
    )

    /** Packs the facing ordinal (low byte) and content rotation (next byte) into one column int. */
    fun packFacing(facingOrdinal: Int, rotation: ContentRotation): Int =
        (facingOrdinal and 0xFF) or ((rotation.quarterTurns and 0xFF) shl 8)

    /** Extracts the facing ordinal from a [packFacing] value; legacy rows (rotation=0) decode unchanged. */
    fun unpackFacingOrdinal(packed: Int): Int = packed and 0xFF

    /** Extracts the content rotation from a [packFacing] value. */
    fun unpackRotation(packed: Int): ContentRotation =
        ContentRotation.fromQuarterTurns((packed shr 8) and 0xFF)

    /** Packs a 3D position into a 64-bit long. */
    fun packPos(x: Int, y: Int, z: Int): Long =
        ((x and 0x3FFFFFF).toLong() shl 38) or ((z and 0x3FFFFFF).toLong() shl 12) or (y and 0xFFF).toLong()

    /** Unpacks a 64-bit long into a 3D position. */
    fun unpackPos(packed: Long) = Triple(
        (packed shr 38).toInt(),
        (packed shl 52 shr 52).toInt(),
        (packed shl 26 shr 38).toInt()
    )

    /** Packs two 16-bit integers into a 64-bit long. */
    fun packInts(high: Int, low: Int): Long =
        (high.toLong() shl 32) or (low.toLong() and 0xFFFFFFFFL)

    /** Unpacks a 64-bit long into two 16-bit integers. */
    fun unpackInts(packed: Long): Pair<Int, Int> =
        (packed shr 32).toInt() to packed.toInt()

    /** Converts a UUID to a byte array. */
    fun UUID.toBytes(): ByteArray = ByteBuffer.allocate(16).apply {
        putLong(mostSignificantBits); putLong(leastSignificantBits)
    }.array()

    /** Converts a byte array to a UUID. */
    fun ByteArray.toUUID(): UUID = ByteBuffer.wrap(this).let { UUID(it.getLong(), it.getLong()) }
}
