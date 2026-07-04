package com.dreamdisplays.platform.server.datatypes

import com.dreamdisplays.api.display.model.ContentRotation
import com.dreamdisplays.platform.server.utils.RegionUtil
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import java.util.*
import java.util.UUID.randomUUID

/**
 * Shared selection data.
 *
 * Per-platform concrete classes ([PaperSelectionData], [VanillaSelectionData]) carry the actual
 * coordinate types; only readiness state and reset are shared on this interface.
 */
interface SelectionData {
    var isReady: Boolean
    fun reset()
}

/**
 * Player's current selection for a feature display.
 *
 * @param player The player making the choice.
 *
 * @property pos1 One corner of the selected area.
 * @property pos2 Opposite corner of the selected area.
 * @property isReady Boolean indicating if the selection is complete.
 */
@PaperOnly
@NullMarked
class PaperSelectionData(player: Player) : SelectionData {
    var pos1: Location? = null
    var pos2: Location? = null
    override var isReady: Boolean = false
    private var face: BlockFace? = null

    /** Player's horizontal look cardinal at first-point time; orients floor/ceiling content. */
    private var horizontal: BlockFace = BlockFace.NORTH
    private val playerId: UUID = player.uniqueId

    /** Sets the facing direction of the future display. */
    fun setFace(face: BlockFace) {
        this.face = face
    }

    /** Records the player's horizontal look cardinal, used to rotate floor/ceiling content. */
    fun setHorizontal(face: BlockFace) {
        this.horizontal = face
    }

    /** Returns the stored facing direction, or [NORTH] if none was set. */
    fun getFace(): BlockFace = face ?: BlockFace.NORTH

    /** Resets the selection state. */
    override fun reset() {
        pos1 = null
        pos2 = null
        isReady = false
        face = null
    }

    /**
     * Builds a finalized [PaperDisplayData] from the current selection.
     *
     * Throws if any corner or face is not yet set.
     */
    fun generateDisplayData(): PaperDisplayData {
        val p1 = requireNotNull(pos1) { "Position 1 is null" }
        val p2 = requireNotNull(pos2) { "Position 2 is null" }
        val f = requireNotNull(face) { "Face is null" }

        val region = RegionUtil.calculateRegion(p1, p2)
        val dPos1 = region.getMinLocation(p1.world)
        val dPos2 = region.getMaxLocation(p1.world)

        val isVertical = f == BlockFace.UP || f == BlockFace.DOWN
        val screenWidth = region.screenWidth(isVertical)
        val screenHeight = region.screenHeight(isVertical)
        val rotation = if (isVertical) horizontalRotation(horizontal) else ContentRotation.NONE

        return PaperDisplayData(randomUUID(), playerId, dPos1, dPos2, screenWidth, screenHeight, f, rotation)
    }

    /** Maps a horizontal cardinal to a content rotation for floor / ceiling screens. */
    private fun horizontalRotation(face: BlockFace): ContentRotation = when (face) {
        BlockFace.NORTH -> ContentRotation.NONE
        BlockFace.EAST -> ContentRotation.RIGHT
        BlockFace.SOUTH -> ContentRotation.HALF_TURN
        BlockFace.WEST -> ContentRotation.LEFT
        else -> ContentRotation.NONE
    }
}

/**
 * Vanilla implementation of [SelectionData], shared by `Fabric` and `NeoForge`.
 */
class VanillaSelectionData : SelectionData {
    var pos1: BlockPos? = null
    var pos2: BlockPos? = null
    var worldKey: String? = null
    var facing: Direction = Direction.NORTH

    /** Player's horizontal look cardinal at first-point time; orients floor/ceiling content. */
    var horizontalFacing: Direction = Direction.NORTH
    override var isReady: Boolean = false

    /** Resets all selection state back to defaults. */
    override fun reset() {
        pos1 = null
        pos2 = null
        worldKey = null
        facing = Direction.NORTH
        horizontalFacing = Direction.NORTH
        isReady = false
    }

    /** Returns the AABB covering the selected region, or `null` if either corner is missing. */
    fun selectionBox(): AABB? {
        val r = region() ?: return null
        return AABB(
            r.minX.toDouble(), r.minY.toDouble(), r.minZ.toDouble(),
            (r.maxX + 1).toDouble(), (r.maxY + 1).toDouble(), (r.maxZ + 1).toDouble(),
        )
    }

    /** Returns `true` if [pos] lies within the selected bounding box. */
    fun contains(pos: BlockPos): Boolean {
        val p1 = pos1 ?: return false
        val p2 = pos2 ?: return false
        return RegionUtil.isInBoundaries(p1, p2, pos)
    }

    /** Computes dimension data for the selection, or `null` if either corner is missing. */
    fun region(): RegionUtil.RegionData? {
        val p1 = pos1 ?: return null
        val p2 = pos2 ?: return null
        return RegionUtil.calculateRegion(p1, p2)
    }

    /**
     * Builds a [VanillaDisplayData] from the current selection.
     *
     * Throws if region or worldKey is not yet set.
     */
    fun generateDisplayData(ownerId: UUID): VanillaDisplayData {
        val r = requireNotNull(region()) { "region is null" }
        val wk = requireNotNull(worldKey) { "worldKey is null" }
        val isVertical = facing == Direction.UP || facing == Direction.DOWN
        return VanillaDisplayData(
            id = randomUUID(),
            ownerId = ownerId,
            worldKey = wk,
            pos1 = r.getMinBlockPos(),
            pos2 = r.getMaxBlockPos(),
            width = r.screenWidth(isVertical),
            height = r.screenHeight(isVertical),
            facing = facing,
            rotation = if (isVertical) horizontalRotation(horizontalFacing) else ContentRotation.NONE,
        )
    }

    /** Maps a horizontal cardinal to a content rotation for floor/ceiling screens. */
    private fun horizontalRotation(dir: Direction): ContentRotation = when (dir) {
        Direction.NORTH -> ContentRotation.NONE
        Direction.EAST -> ContentRotation.RIGHT
        Direction.SOUTH -> ContentRotation.HALF_TURN
        Direction.WEST -> ContentRotation.LEFT
        else -> ContentRotation.NONE
    }
}
