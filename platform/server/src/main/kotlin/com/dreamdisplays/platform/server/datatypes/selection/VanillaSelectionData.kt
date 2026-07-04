package com.dreamdisplays.platform.server.datatypes.selection

import com.dreamdisplays.api.display.model.ContentRotation
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.utils.RegionUtil
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import java.util.UUID.randomUUID
import java.util.UUID

/**
 * Vanilla implementation of [SelectionData], shared by `Fabric` and `NeoForge`.
 */
class VanillaSelectionData : BaseSelectionData() {
    /** One corner of the selection area. */
    var pos1: BlockPos? = null

    /** Opposite corner of the selection area. */
    var pos2: BlockPos? = null

    /** Unique identifier for the world the selection is in. */
    var worldKey: String? = null

    /** Block face the player is facing at first-point time; orients wall content. */
    var facing: Direction = Direction.NORTH

    /** Player's horizontal look cardinal at first-point time; orients floor/ceiling content. */
    var horizontalFacing: Direction = Direction.NORTH

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
        val r = requireNotNull(region()) { "region is null." }
        val wk = requireNotNull(worldKey) { "worldKey is null." }
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
            rotation = if (isVertical) horizontalFacing.toCardinal().toContentRotation() else ContentRotation.NONE,
        )
    }

    /** Maps this direction to the shared horizontal [Cardinal], defaulting to [Cardinal.NORTH]. */
    private fun Direction.toCardinal(): Cardinal = when (this) {
        Direction.NORTH -> Cardinal.NORTH
        Direction.EAST -> Cardinal.EAST
        Direction.SOUTH -> Cardinal.SOUTH
        Direction.WEST -> Cardinal.WEST
        else -> Cardinal.NORTH
    }
}
