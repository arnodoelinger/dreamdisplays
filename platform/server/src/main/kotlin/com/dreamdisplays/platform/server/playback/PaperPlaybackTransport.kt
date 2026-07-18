package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.protocol.DreamPacket
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.utils.PlatformUtil
import com.dreamdisplays.platform.server.utils.net.PaperV2Networking
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.jspecify.annotations.NullMarked
import java.util.*

/** `Paper` implementation of [PlaybackTransport]: v2 envelopes via [PaperV2Networking]. */
@PaperOnly
@NullMarked
object PaperPlaybackTransport : PlaybackTransport {
    /** Returns the current time in milliseconds. */
    override fun nowMs(): Long = System.currentTimeMillis()

    /** Broadcasts [packet] to all v2 players in [display]'s receivers. */
    override fun broadcast(display: DisplayData, packet: DreamPacket) {
        val paper = display as? PaperDisplayData ?: return
        if (PlatformUtil.isFolia) {
            DisplayManager.sendV2ToTrackedNearbyPlayers(paper, packet)
            return
        }
        val receivers = DisplayManager.getReceivers(paper).filter { V2PlayerTracker.isV2(it.uniqueId) }
        if (receivers.isNotEmpty()) PaperV2Networking.send(receivers, packet)
    }

    /** Sends [packet] to a single player with [playerId]. */
    override fun sendTo(playerId: UUID, packet: DreamPacket) {
        if (PlatformUtil.isFolia) {
            Scheduler.runTrackedPlayer(playerId) { player ->
                if (V2PlayerTracker.isV2(playerId)) PaperV2Networking.send(listOf(player), packet)
            }
            return
        }
        val player = PaperServer.getInstance().server.getPlayer(playerId) ?: return
        if (V2PlayerTracker.isV2(playerId)) PaperV2Networking.send(listOf(player), packet)
    }

    /** UUIDs of players currently in range of [display] (watch-party nearby / ready-check denominator). */
    override fun nearbyPlayerIds(display: DisplayData): List<UUID> {
        val paper = display as? PaperDisplayData ?: return emptyList()
        if (PlatformUtil.isFolia) return DisplayManager.getTrackedNearbyPlayerIds(paper)
        return DisplayManager.getReceivers(paper).map { it.uniqueId }
    }

    /** Display name for [playerId], or null if unknown / offline. */
    override fun playerName(playerId: UUID): String? {
        if (PlatformUtil.isFolia) return Scheduler.trackedPlayerName(playerId)
        return PaperServer.getInstance().server.getPlayer(playerId)?.name
    }

    /** True if [playerId] is recognized as an admin (op / delete permission). */
    override fun isAdmin(playerId: UUID): Boolean {
        if (PlatformUtil.isFolia) return Scheduler.trackedPlayerIsAdmin(playerId)
        return PaperServer.getInstance().server.getPlayer(playerId)
            ?.hasPermission(PaperServer.config.permissions.delete) == true
    }

    /** UUIDs of every online player. */
    override fun onlinePlayerIds(): List<UUID> = PaperServer.getInstance().server.onlinePlayers.map { it.uniqueId }

    /** Squared distance from [playerId] to (`x`, `y`, `z`) in [world], or null when offline or in a different world. */
    override fun playerDistanceSq(playerId: UUID, world: String, x: Double, y: Double, z: Double): Double? {
        val loc = PaperServer.getInstance().server.getPlayer(playerId)?.location ?: return null
        if (loc.world?.name != world) return null
        val dx = loc.x - x
        val dy = loc.y - y
        val dz = loc.z - z
        return dx * dx + dy * dy + dz * dz
    }

    /** Sends [display]'s `DisplayInfo` to one [playerId], regardless of render distance. */
    override fun sendDisplayInfo(playerId: UUID, display: DisplayData, forced: Boolean) {
        val paper = display as? PaperDisplayData ?: return
        val player = PaperServer.getInstance().server.getPlayer(playerId) ?: return
        DisplayManager.sendUpdate(paper, listOf(player), forced)
    }

    /** Builds a synthetic 1x1 [PaperDisplayData] at the origin of the first loaded world. */
    override fun createVirtualDisplay(id: UUID, ownerId: UUID): DisplayData? {
        val world = PaperServer.getInstance().server.worlds.firstOrNull() ?: return null
        val loc = org.bukkit.Location(world, 0.0, 0.0, 0.0)
        return PaperDisplayData(id, ownerId, loc, loc, 1, 1, virtual = true)
    }
}
