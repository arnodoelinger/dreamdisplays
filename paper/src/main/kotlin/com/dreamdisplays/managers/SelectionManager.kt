package com.dreamdisplays.managers

import com.dreamdisplays.datatypes.SelectionData
import com.dreamdisplays.utils.MessageUtil.sendMessage
import com.dreamdisplays.utils.RegionUtil.isInBoundaries
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import java.util.*

/**
 * Manages player selections for display creation.
 */
object SelectionManager {
    val selectionPoints = mutableMapOf<UUID, SelectionData>()

    fun setFirstPoint(player: Player, loc: Location, face: Any) {
        val sel = selectionPoints.getOrPut(player.uniqueId) { SelectionData(player) }
        if (sel.pos1?.world != loc.world || sel.pos2?.world != loc.world) sel.reset()
        sel.pos1 = loc.clone()
        sel.setFace(face as BlockFace)
        sel.isReady = false
        sendMessage(player, "firstPointSelected")
    }

    fun setSecondPoint(player: Player, loc: Location) {
        val sel = selectionPoints[player.uniqueId] ?: return
        if (sel.pos1 == null || sel.pos1?.world != loc.world) {
            sel.reset()
            sendMessage(player, "noDisplayTerritories")
            return
        }
        sel.pos2 = loc.clone()
        sel.isReady = true
        sendMessage(player, "secondPointSelected")
    }

    fun isLocationSelected(loc: Location): Boolean =
        selectionPoints.values.any { it.isReady && it.contains(loc) }

    fun resetSelection(player: Player) {
        selectionPoints.remove(player.uniqueId)?.reset()
    }

    private fun SelectionData.contains(loc: Location): Boolean {
        val p1 = pos1 ?: return false
        val p2 = pos2 ?: return false
        return isInBoundaries(p1, p2, loc)
    }

    private fun SelectionData.reset() {
        pos1 = null
        pos2 = null
        isReady = false
    }
}
