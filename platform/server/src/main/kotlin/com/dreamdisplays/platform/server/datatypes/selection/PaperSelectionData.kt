package com.dreamdisplays.platform.server.datatypes.selection

import com.dreamdisplays.api.display.model.ContentRotation
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.utils.RegionUtil
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import java.util.UUID.randomUUID
import java.util.UUID

/**
 * Player's current selection for a feature display.
 */
@PaperOnly
@NullMarked
class PaperSelectionData(player: Player) : BaseSelectionData() {
    /** One corner of the selected area. */
    var pos1: Location? = null

    /** Opposite corner of the selected area. */
    var pos2: Location? = null

    /** Block face the player is facing at first-point time; orients wall content. */
    private var face: BlockFace? = null

    /** Player's horizontal look cardinal at first-point time; orients floor / ceiling content. */
    private var horizontal: BlockFace = BlockFace.NORTH

    /** Unique identifier for the player making the selection. */
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
        /** Position 1, position 2, and face must be non-null to generate display data. */
        val p1 = requireNotNull(pos1) { "Position 1 is null" }
        val p2 = requireNotNull(pos2) { "Position 2 is null" }
        val f = requireNotNull(face) { "Face is null" }

        /** Calculate the region and determine the display dimensions. */
        val region = RegionUtil.calculateRegion(p1, p2)
        val dPos1 = region.getMinLocation(p1.world)
        val dPos2 = region.getMaxLocation(p1.world)

        /**
         * Determine if the display is vertical (facing `UP` or `DOWN`) or horizontal (facing `NORTH`, `EAST`, `SOUTH`, `WEST`).
         * This affects how we calculate the width and height of the display.
         */
        val isVertical = f == BlockFace.UP || f == BlockFace.DOWN
        val screenWidth = region.screenWidth(isVertical)
        val screenHeight = region.screenHeight(isVertical)
        val rotation = if (isVertical) horizontal.toCardinal().toContentRotation() else ContentRotation.NONE

        return PaperDisplayData(randomUUID(), playerId, dPos1, dPos2, screenWidth, screenHeight, f, rotation)
    }

    /** Maps this face to the shared horizontal [Cardinal], defaulting to [Cardinal.NORTH]. */
    private fun BlockFace.toCardinal(): Cardinal = when (this) {
        BlockFace.NORTH -> Cardinal.NORTH
        BlockFace.EAST -> Cardinal.EAST
        BlockFace.SOUTH -> Cardinal.SOUTH
        BlockFace.WEST -> Cardinal.WEST
        else -> Cardinal.NORTH
    }
}
