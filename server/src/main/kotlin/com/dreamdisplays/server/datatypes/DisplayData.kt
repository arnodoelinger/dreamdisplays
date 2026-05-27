package com.dreamdisplays.server.datatypes

import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.util.BoundingBox
import org.jspecify.annotations.NullMarked
import java.util.*

/**
 * Shared display data.
 *
 * Per-platform concrete classes ([PaperDisplayData], [FabricDisplayData]) carry platform-specific
 * position / box types; shared state and identity live on this interface.
 */
interface DisplayData {
    val id: UUID
    val ownerId: UUID
    val width: Int
    val height: Int
    var url: String
    var lang: String
    var isSync: Boolean
    var isLocked: Boolean
    var duration: Long?
}

/**
 * Data class representing a display in the Minecraft world.
 *
 * @param id unique identifier for the display.
 * @param ownerId unique identifier for the owner of the display.
 * @param pos1 one corner of the display area.
 * @param pos2 opposite corner of the display area.
 * @param width width of the display in blocks.
 * @param height height of the display in blocks.
 * @param facing direction the display is facing. Default is `NORTH`.
 *
 * @property url URL of the content to be displayed.
 * @property lang language code for the display content.
 * @property isSync boolean indicating if the display is synchronized.
 * @property duration optional duration for which the content should be displayed.
 * @property box bounding box of the display area.
 */
@PaperOnly @NullMarked class PaperDisplayData(
    override val id: UUID,
    override val ownerId: UUID,
    val pos1: Location,
    val pos2: Location,
    override val width: Int,
    override val height: Int,
    val facing: BlockFace = BlockFace.NORTH,
) : DisplayData {
    override var url: String = ""
    override var lang: String = ""
    override var isSync: Boolean = false
    override var isLocked: Boolean = true
    override var duration: Long? = null

    val box: BoundingBox = BoundingBox(
        minOf(pos1.blockX, pos2.blockX).toDouble(),
        minOf(pos1.blockY, pos2.blockY).toDouble(),
        minOf(pos1.blockZ, pos2.blockZ).toDouble(),
        (maxOf(pos1.blockX, pos2.blockX) + 1).toDouble(),
        (maxOf(pos1.blockY, pos2.blockY) + 1).toDouble(),
        (maxOf(pos1.blockZ, pos2.blockZ) + 1).toDouble()
    )
}

/**
 * `Fabric`-specific implementation of [DisplayData].
 */
@FabricOnly class FabricDisplayData(
    override val id: UUID,
    override val ownerId: UUID,
    val worldKey: String,
    val pos1: BlockPos,
    val pos2: BlockPos,
    override val width: Int,
    override val height: Int,
    val facing: Direction,
) : DisplayData {
    override var url: String = ""
    override var lang: String = ""
    override var isSync: Boolean = false
    override var isLocked: Boolean = true
    override var duration: Long? = null

    val minX = minOf(pos1.x, pos2.x)
    val minY = minOf(pos1.y, pos2.y)
    val minZ = minOf(pos1.z, pos2.z)
    val maxX = maxOf(pos1.x, pos2.x)
    val maxY = maxOf(pos1.y, pos2.y)
    val maxZ = maxOf(pos1.z, pos2.z)

    val box: AABB = AABB(
        minX.toDouble(), minY.toDouble(), minZ.toDouble(),
        (maxX + 1).toDouble(), (maxY + 1).toDouble(), (maxZ + 1).toDouble()
    )
}
