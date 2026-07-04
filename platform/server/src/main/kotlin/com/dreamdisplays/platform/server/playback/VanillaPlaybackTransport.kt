package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.protocol.DreamPacket
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.net.VanillaNetworking
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import net.minecraft.server.MinecraftServer
import java.util.UUID

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
        return VanillaDisplayActions.isOpLevel2(player)
    }
}
