package com.dreamdisplays.platform.server.datatypes

import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.display.model.ContentRotation
import com.dreamdisplays.api.playback.PlaybackPermissions
import com.dreamdisplays.platform.server.utils.RegionUtil
import io.github.arnodoelinger.platformweaver.PaperOnly
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
 * Per-platform concrete classes ([PaperDisplayData], [VanillaDisplayData]) carry platform-specific
 * position / box types; shared state and identity live on this interface.
 */
interface DisplayData {
    val id: UUID
    val ownerId: UUID
    val width: Int
    val height: Int

    /** Content rotation; only meaningful for floor / ceiling (`UP` / `DOWN`) facings. */
    val rotation: ContentRotation
    var url: String
    var lang: String

    /** The persistent base playback mode. Source of truth; never [PlaybackMode.WATCH_PARTY]. */
    var mode: PlaybackMode

    var isLocked: Boolean
    var duration: Long?

    /** Legacy mirror of [mode] for frozen-v1 peers; true only when the mode is [PlaybackMode.SYNCED]. */
    val isSync: Boolean get() = mode == PlaybackMode.SYNCED

    /** Max video height clients must not exceed (0 = uncapped, 360 for [PlaybackMode.BROADCAST]). */
    val qualityCap: Int
        get() = if (mode == PlaybackMode.BROADCAST) PlaybackPermissions.BROADCAST_QUALITY_CAP else 0
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
@PaperOnly
@NullMarked
class PaperDisplayData(
    override val id: UUID,
    override val ownerId: UUID,
    val pos1: Location,
    val pos2: Location,
    override val width: Int,
    override val height: Int,
    val facing: BlockFace = BlockFace.NORTH,
    override val rotation: ContentRotation = ContentRotation.NONE,
) : DisplayData {
    override var url: String = ""
    override var lang: String = ""
    override var mode: PlaybackMode = PlaybackMode.LOCAL
    override var isLocked: Boolean = true
    override var duration: Long? = null

    private val region = RegionUtil.calculateRegion(pos1, pos2)

    val box: BoundingBox = BoundingBox(
        region.minX.toDouble(), region.minY.toDouble(), region.minZ.toDouble(),
        (region.maxX + 1).toDouble(), (region.maxY + 1).toDouble(), (region.maxZ + 1).toDouble(),
    )
}

/**
 * Vanilla implementation of [DisplayData], shared by `Fabric` and `NeoForge`.
 */
class VanillaDisplayData(
    override val id: UUID,
    override val ownerId: UUID,
    val worldKey: String,
    val pos1: BlockPos,
    val pos2: BlockPos,
    override val width: Int,
    override val height: Int,
    val facing: Direction,
    override val rotation: ContentRotation = ContentRotation.NONE,
) : DisplayData {
    override var url: String = ""
    override var lang: String = ""
    override var mode: PlaybackMode = PlaybackMode.LOCAL
    override var isLocked: Boolean = true
    override var duration: Long? = null

    private val region = RegionUtil.calculateRegion(pos1, pos2)

    val minX = region.minX
    val minY = region.minY
    val minZ = region.minZ
    val maxX = region.maxX
    val maxY = region.maxY
    val maxZ = region.maxZ

    val box: AABB = AABB(
        minX.toDouble(), minY.toDouble(), minZ.toDouble(),
        (maxX + 1).toDouble(), (maxY + 1).toDouble(), (maxZ + 1).toDouble(),
    )
}
