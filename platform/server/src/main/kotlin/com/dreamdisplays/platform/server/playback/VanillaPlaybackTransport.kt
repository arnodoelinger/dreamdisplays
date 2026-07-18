package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.protocol.DreamPacket
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions
import com.dreamdisplays.platform.server.utils.net.VanillaNetworking
import com.dreamdisplays.platform.server.utils.net.VanillaPacketUtil
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import java.util.*

/**
 * Vanilla Minecraft API implementation of [PlaybackTransport], shared by `Fabric` and `NeoForge`.
 */
object VanillaPlaybackTransport : PlaybackTransport {
    /** The running server instance, bound on `SERVER_STARTED`. */
    @Volatile
    private var server: MinecraftServer? = null

    /** Binds the running server; called from `SERVER_STARTED` / `ServerStartedEvent`. */
    fun bind(server: MinecraftServer) {
        this.server = server
    }

    /** Returns the current time in milliseconds. */
    override fun nowMs(): Long = System.currentTimeMillis()

    /** Broadcasts [packet] to all v2 players in [display]'s receivers. */
    override fun broadcast(display: DisplayData, packet: DreamPacket) {
        val s = server ?: return
        val vanilla = display as? VanillaDisplayData ?: return
        val receivers = DisplayManager.getReceivers(vanilla, s).filter { V2PlayerTracker.isV2(it.uuid) }
        if (receivers.isNotEmpty()) VanillaNetworking.adapter.sendV2(receivers, packet)
    }

    /** Sends [packet] to a single player with [playerId]. */
    override fun sendTo(playerId: UUID, packet: DreamPacket) {
        val s = server ?: return
        val player = s.playerList.getPlayer(playerId) ?: return
        if (V2PlayerTracker.isV2(playerId)) VanillaNetworking.adapter.sendV2(listOf(player), packet)
    }

    /** UUIDs of players currently in range of [display] (watch-party nearby / ready-check denominator). */
    override fun nearbyPlayerIds(display: DisplayData): List<UUID> {
        val s = server ?: return emptyList()
        val vanilla = display as? VanillaDisplayData ?: return emptyList()
        return DisplayManager.getReceivers(vanilla, s).map { it.uuid }
    }

    /** Display name for [playerId], or null if unknown / offline. */
    override fun playerName(playerId: UUID): String? =
        server?.playerList?.getPlayer(playerId)?.gameProfile?.name

    /** True if [playerId] is recognized as an admin (op / delete permission). */
    override fun isAdmin(playerId: UUID): Boolean {
        val player = server?.playerList?.getPlayer(playerId) ?: return false
        return VanillaDisplayActions.isAdmin(player)
    }

    /** UUIDs of every online player. */
    override fun onlinePlayerIds(): List<UUID> = server?.playerList?.players?.map { it.uuid } ?: emptyList()

    /** Squared distance from [playerId] to (`x`, `y`, `z`) in [world], or null when offline or in a different world. */
    override fun playerDistanceSq(playerId: UUID, world: String, x: Double, y: Double, z: Double): Double? {
        val player = server?.playerList?.getPlayer(playerId) ?: return null
        if (RegionUtil.getPlayerLevelKey(player) != world) return null
        val dx = player.x - x
        val dy = player.y - y
        val dz = player.z - z
        return dx * dx + dy * dy + dz * dz
    }

    /** Sends [display]'s `DisplayInfo` to one [playerId], regardless of render distance. */
    override fun sendDisplayInfo(playerId: UUID, display: DisplayData, forced: Boolean) {
        val vanilla = display as? VanillaDisplayData ?: return
        val player = server?.playerList?.getPlayer(playerId) ?: return
        VanillaPacketUtil.sendDisplayInfo(listOf(player), vanilla, forced)
    }

    /** Builds a synthetic 1x1 [VanillaDisplayData] at the origin of the first loaded level. */
    override fun createVirtualDisplay(id: UUID, ownerId: UUID): DisplayData? {
        val s = server ?: return null
        val worldKey = RegionUtil.getLevelKey(s.overworld())
        val pos = BlockPos(0, 0, 0)
        return VanillaDisplayData(id, ownerId, worldKey, pos, pos, 1, 1, Direction.NORTH, virtual = true)
    }
}
