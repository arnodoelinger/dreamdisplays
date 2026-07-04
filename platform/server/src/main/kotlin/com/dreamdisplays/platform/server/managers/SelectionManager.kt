package com.dreamdisplays.platform.server.managers

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.VanillaSelectionData
import com.dreamdisplays.platform.server.datatypes.PaperSelectionData
import com.dreamdisplays.platform.server.datatypes.SelectionData
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import io.github.arnodoelinger.platformweaver.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks player block selections used when creating a new display. Stores the first and
 * second corner positions and exposes helpers to validate and reset the current selection.
 */
object SelectionManager {
    val selectionPoints: MutableMap<UUID, SelectionData> = ConcurrentHashMap()

    /** Records the first selection corner for [player] and resets stale state if the world changed. */
    @PaperOnly
    fun setFirstPoint(player: Player, loc: Location, face: Any) {
        val sel = (selectionPoints[player.uniqueId] as? PaperSelectionData)
            ?: PaperSelectionData(player).also { selectionPoints[player.uniqueId] = it }
        if (sel.pos1?.world != loc.world || sel.pos2?.world != loc.world) sel.reset()
        sel.pos1 = loc.clone()
        sel.setFace(face as BlockFace)
        sel.setHorizontal(player.facing)
        sel.isReady = false
        MessageUtil.sendMessage(player, "firstPointSelected")
    }

    /** Records the first selection corner for [player] and resets stale state if the world changed. */
    fun setFirstPoint(player: ServerPlayer, pos: BlockPos, worldKey: String, face: Direction) {
        val sel = (selectionPoints[player.uuid] as? VanillaSelectionData)
            ?: VanillaSelectionData().also { selectionPoints[player.uuid] = it }
        if (sel.worldKey != worldKey) sel.reset()
        sel.pos1 = pos
        sel.worldKey = worldKey
        sel.facing = face
        sel.horizontalFacing = player.direction
        sel.isReady = false
        MessageUtil.sendMessage(player, "firstPointSelected")
    }

    /** Records the second selection corner, validating the worlds match the first point. */
    @PaperOnly
    fun setSecondPoint(player: Player, loc: Location) {
        val sel = selectionPoints[player.uniqueId] as? PaperSelectionData ?: return
        if (sel.pos1 == null || sel.pos1?.world != loc.world) {
            sel.reset()
            MessageUtil.sendMessageWithMaterials(
                player, "noDisplayTerritories",
                PaperServer.config.settings.selectionMaterial, PaperServer.config.settings.baseMaterial
            )
            return
        }
        sel.pos2 = loc.clone()
        sel.isReady = true
        MessageUtil.sendMessage(player, "secondPointSelected")
    }

    /** Records the second selection corner, validating the worlds match the first point. */
    fun setSecondPoint(player: ServerPlayer, pos: BlockPos, worldKey: String) {
        val sel = selectionPoints[player.uuid] as? VanillaSelectionData ?: return
        if (sel.pos1 == null || sel.worldKey != worldKey) {
            sel.reset()
            MessageUtil.sendMessageWithMaterials(
                player, "noDisplayTerritories",
                VanillaServerState.config.settings.selectionMaterial, VanillaServerState.config.settings.baseMaterial
            )
            return
        }
        sel.pos2 = pos
        sel.isReady = true
        MessageUtil.sendMessage(player, "secondPointSelected")
    }

    /** Returns true if [loc] lies within any player's finalized selection. */
    @PaperOnly
    fun isLocationSelected(loc: Location): Boolean =
        selectionPoints.values.filterIsInstance<PaperSelectionData>().any { it.isReady && it.contains(loc) }

    /** Returns true if [pos] lies within any player's finalized selection in [worldKey]. */
    fun isLocationSelected(pos: BlockPos, worldKey: String): Boolean =
        selectionPoints.values.filterIsInstance<VanillaSelectionData>().any { sel ->
            sel.isReady && sel.worldKey == worldKey && sel.contains(pos)
        }

    /** Clears the selection for the player identified by [uuid]. */
    fun resetSelection(uuid: UUID) = selectionPoints.remove(uuid)?.reset()

    /** Clears [player]'s current selection. */
    @PaperOnly
    fun resetSelection(player: Player) = resetSelection(player.uniqueId)

    /** Clears [player]'s current selection. */
    fun resetSelection(player: ServerPlayer) = resetSelection(player.uuid)

    /** Returns true if [loc] is inside the bounding box defined by this selection. */
    @PaperOnly
    private fun PaperSelectionData.contains(loc: Location): Boolean {
        val p1 = pos1 ?: return false
        val p2 = pos2 ?: return false
        return RegionUtil.isInBoundaries(p1, p2, loc)
    }
}
