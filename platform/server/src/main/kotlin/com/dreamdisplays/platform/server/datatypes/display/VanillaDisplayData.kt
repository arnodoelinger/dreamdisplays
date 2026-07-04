package com.dreamdisplays.platform.server.datatypes.display

import com.dreamdisplays.api.display.model.ContentRotation
import com.dreamdisplays.platform.server.utils.RegionUtil
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import java.util.*

/**
 * Vanilla implementation of [DisplayData], shared by `Fabric` and `NeoForge`.
 */
class VanillaDisplayData(
    /** Unique identifier for the display. */
    override val id: UUID,

    /** Unique identifier for the owner of the display. */
    override val ownerId: UUID,

    /** Unique identifier for the world the display is in. */
    val worldKey: String,

    /** One corner of the display area. */
    val pos1: BlockPos,

    /** Opposite corner of the display area. */
    val pos2: BlockPos,

    /** Width of the display in blocks. */
    override val width: Int,

    /** Height of the display in blocks. */
    override val height: Int,

    /** Direction the display is facing. Default is `NORTH`. */
    val facing: Direction,

    /** Content rotation; only meaningful for floor / ceiling (`UP` / `DOWN`) facings. */
    override val rotation: ContentRotation = ContentRotation.NONE,
) : BaseDisplayData() {
    /** Bounding box of the display area. */
    private val region = RegionUtil.calculateRegion(pos1, pos2)

    /** Region. */
    val minX = region.minX
    val minY = region.minY
    val minZ = region.minZ
    val maxX = region.maxX
    val maxY = region.maxY
    val maxZ = region.maxZ

    /** Bounding box of the display area, calculated from the two corner positions. */
    val box: AABB = AABB(
        minX.toDouble(), minY.toDouble(), minZ.toDouble(),
        (maxX + 1).toDouble(), (maxY + 1).toDouble(), (maxZ + 1).toDouble(),
    )
}
