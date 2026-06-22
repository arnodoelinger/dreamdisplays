package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.protocol.DreamPacket
import com.dreamdisplays.platform.server.datatypes.DisplayData
import com.dreamdisplays.platform.server.datatypes.FabricDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.net.FabricV2Networking
import com.dreamdisplays.platform.server.utils.net.ServerPacketHandler
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import io.github.arsmotorin.ofrat.FabricOnly
import net.minecraft.server.MinecraftServer
import java.util.UUID

/** `Fabric` implementation of [PlaybackTransport]: v2 envelopes via [FabricV2Networking]. */
@FabricOnly object FabricPlaybackTransport : PlaybackTransport {
    @Volatile private var server: MinecraftServer? = null

    /** Binds the running server; called from `SERVER_STARTED`. */
    fun bind(server: MinecraftServer) {
        this.server = server
    }

    /** Returns the current time in milliseconds. */
    override fun nowMs(): Long = System.currentTimeMillis()

    /** Broadcasts [packet] to all v2 players in [display]'s receivers. */
    override fun broadcast(display: DisplayData, packet: DreamPacket) {
        val s = server ?: return
        val fabric = display as? FabricDisplayData ?: return
        val receivers = DisplayManager.getReceivers(fabric, s).filter { V2PlayerTracker.isV2(it.uuid) }
        if (receivers.isNotEmpty()) FabricV2Networking.send(receivers, packet)
    }

    /** Sends [packet] to a single player with [playerId]. */
    override fun sendTo(playerId: UUID, packet: DreamPacket) {
        val s = server ?: return
        val player = s.playerList.getPlayer(playerId) ?: return
        if (V2PlayerTracker.isV2(playerId)) FabricV2Networking.send(listOf(player), packet)
    }

    /** UUIDs of players currently in range of [display] (watch-party nearby / ready-check denominator). */
    override fun nearbyPlayerIds(display: DisplayData): List<UUID> {
        val s = server ?: return emptyList()
        val fabric = display as? FabricDisplayData ?: return emptyList()
        return DisplayManager.getReceivers(fabric, s).map { it.uuid }
    }

    /** Display name for [playerId], or null if unknown / offline. */
    override fun playerName(playerId: UUID): String? =
        server?.playerList?.getPlayer(playerId)?.gameProfile?.name

    /** True if [playerId] is recognized as an admin (op / delete permission). */
    override fun isAdmin(playerId: UUID): Boolean {
        val player = server?.playerList?.getPlayer(playerId) ?: return false
        return ServerPacketHandler.isOpLevel2(player)
    }
}
