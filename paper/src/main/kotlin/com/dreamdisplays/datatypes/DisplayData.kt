package com.dreamdisplays.datatypes

import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.util.BoundingBox
import org.jspecify.annotations.NullMarked
import java.util.*

/**
 * Data class representing a display in the Minecraft world.
 *
 * `Paper` implementation.
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
@NullMarked
class DisplayData(
    val id: UUID,
    val ownerId: UUID,
    val pos1: Location,
    val pos2: Location,
    val width: Int,
    val height: Int,
    val facing: BlockFace = BlockFace.NORTH,
) {
    var url: String = ""
    var lang: String = ""
    var isSync: Boolean = false
    var isLocked: Boolean = true
    var duration: Long? = null

    val box: BoundingBox = BoundingBox(
        minOf(pos1.blockX, pos2.blockX).toDouble(),
        minOf(pos1.blockY, pos2.blockY).toDouble(),
        minOf(pos1.blockZ, pos2.blockZ).toDouble(),
        (maxOf(pos1.blockX, pos2.blockX) + 1).toDouble(),
        (maxOf(pos1.blockY, pos2.blockY) + 1).toDouble(),
        (maxOf(pos1.blockZ, pos2.blockZ) + 1).toDouble()
    )
}
