package com.dreamdisplays.server.datatypes

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import java.util.*

/**
 * Data class representing a display in the Minecraft world.
 *
 * `Fabric server` implementation.
 *
 * @param id Unique identifier for the display.
 * @param ownerId Unique identifier for the owner of the display.
 * @param pos1 One corner of the display area.
 * @param pos2 Opposite corner of the display area.
 * @param width Width of the display in blocks.
 * @param height Height of the display in blocks.
 * @param facing Direction the display is facing. Default is NORTH.
 *
 * @property url URL of the content to be displayed.
 * @property lang Language code for the display content.
 * @property isSync Boolean indicating if the display is synchronized.
 * @property duration Optional duration for which the content should be displayed.
 * @property box Bounding box of the display area.
 *
 */
class DisplayData(
    // Paper start
    val id: UUID,
    val ownerId: UUID,
    // Fabric server start
    val worldKey: String,
    val pos1: BlockPos,
    val pos2: BlockPos,
    // Fabric server end
    val width: Int,
    val height: Int,
    // Fabric server start
    val facing: Direction,
    // Fabric server end
) {
    var url: String = ""
    var lang: String = ""
    var isSync: Boolean = false
    var isLocked: Boolean = true
    var duration: Long? = null

    val minX = minOf(pos1.x, pos2.x)
    val minY = minOf(pos1.y, pos2.y)
    val minZ = minOf(pos1.z, pos2.z)
    val maxX = maxOf(pos1.x, pos2.x)
    val maxY = maxOf(pos1.y, pos2.y)
    val maxZ = maxOf(pos1.z, pos2.z)

    // Fabric server start
    val box: AABB = AABB(
        minX.toDouble(), minY.toDouble(), minZ.toDouble(),
        (maxX + 1).toDouble(), (maxY + 1).toDouble(), (maxZ + 1).toDouble()
    )
    // Fabric server end
    // Paper end
}
