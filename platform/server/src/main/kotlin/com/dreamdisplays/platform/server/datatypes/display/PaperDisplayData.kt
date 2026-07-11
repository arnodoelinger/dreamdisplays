package com.dreamdisplays.platform.server.datatypes.display

import com.dreamdisplays.api.display.model.ContentRotation
import com.dreamdisplays.platform.server.utils.RegionUtil
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.util.BoundingBox
import org.jspecify.annotations.NullMarked
import java.util.*

/**
 * Data class representing a display in the Minecraft world.
 */
@PaperOnly
@NullMarked
class PaperDisplayData(
    /** Unique identifier for the display. */
    override val id: UUID,

    /** Unique identifier for the owner of the display. */
    override val ownerId: UUID,

    /** One corner of the display area. */
    val pos1: Location,

    /** Opposite corner of the display area. */
    val pos2: Location,

    /** Width of the display in blocks. */
    override val width: Int,

    /** Height of the display in blocks. */
    override val height: Int,

    /** Direction the display is facing. Default is `NORTH`. */
    val facing: BlockFace = BlockFace.NORTH,

    /** Content rotation; only meaningful for floor / ceiling (`UP` / `DOWN`) facings. */
    override val rotation: ContentRotation = ContentRotation.NONE,

    /** True for the synthetic display backing a URL-only fullscreen broadcast. */
    virtual: Boolean = false,
) : BaseDisplayData(virtual) {
    /** Bounding box of the display area. */
    private val region = RegionUtil.calculateRegion(pos1, pos2)

    /** Bounding box of the display area, calculated from the two corner positions. */
    val box: BoundingBox = BoundingBox(
        region.minX.toDouble(), region.minY.toDouble(), region.minZ.toDouble(),
        (region.maxX + 1).toDouble(), (region.maxY + 1).toDouble(), (region.maxZ + 1).toDouble(),
    )
}
