package com.dreamdisplays.datatypes

import com.dreamdisplays.utils.OutlinerUtil.showOutline
import com.dreamdisplays.utils.RegionUtil.calculateRegion
import org.bukkit.Bukkit.getPlayer
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockFace.NORTH
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import java.util.*
import java.util.UUID.randomUUID

/**
 * Player's current selection for a feature display.
 * @param player The player making the selection.
 *
 * @property pos1 One corner of the selected area.
 * @property pos2 Opposite corner of the selected area.
 * @property isReady Boolean indicating if the selection is complete.
 * @property face The direction the selection is facing.
 * @property playerId Unique identifier for the player.
 *
 */
@NullMarked
class SelectionData(player: Player) {
    var pos1: Location? = null
    var pos2: Location? = null
    var isReady: Boolean = false

    private var face: BlockFace? = null
    private val playerId: UUID = player.uniqueId

    fun setFace(face: BlockFace) {
        this.face = face
    }

    fun getFace(): BlockFace = face ?: NORTH

    fun drawBox() {
        val p1 = pos1 ?: return
        val p2 = pos2 ?: return
        val player = getPlayer(playerId) ?: return
        showOutline(player, p1, p2)
    }

    fun generateDisplayData(): DisplayData {
        val p1 = requireNotNull(pos1) { "Position 1 is null" }
        val p2 = requireNotNull(pos2) { "Position 2 is null" }
        val f = requireNotNull(face) { "Face is null" }

        val region = calculateRegion(p1, p2)
        val dPos1 = region.getMinLocation(p1.world)
        val dPos2 = region.getMaxLocation(p1.world)

        return DisplayData(randomUUID(), playerId, dPos1, dPos2, region.width, region.height, f)
    }
}
