package com.dreamdisplays.server.playback

import com.dreamdisplays.protocol.DreamPacket
import com.dreamdisplays.server.datatypes.DisplayData
import com.dreamdisplays.server.datatypes.FabricDisplayData
import com.dreamdisplays.server.managers.DisplayManager
import com.dreamdisplays.server.utils.net.FabricV2Networking
import com.dreamdisplays.server.utils.net.ServerPacketHandler
import com.dreamdisplays.server.utils.net.V2PlayerTracker
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

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun broadcast(display: DisplayData, packet: DreamPacket) {
        val s = server ?: return
        val fabric = display as? FabricDisplayData ?: return
        val receivers = DisplayManager.getReceivers(fabric, s).filter { V2PlayerTracker.isV2(it.uuid) }
        if (receivers.isNotEmpty()) FabricV2Networking.send(receivers, packet)
    }

    override fun sendTo(playerId: UUID, packet: DreamPacket) {
        val s = server ?: return
        val player = s.playerList.getPlayer(playerId) ?: return
        if (V2PlayerTracker.isV2(playerId)) FabricV2Networking.send(listOf(player), packet)
    }

    override fun nearbyPlayerIds(display: DisplayData): List<UUID> {
        val s = server ?: return emptyList()
        val fabric = display as? FabricDisplayData ?: return emptyList()
        return DisplayManager.getReceivers(fabric, s).map { it.uuid }
    }

    override fun playerName(playerId: UUID): String? =
        server?.playerList?.getPlayer(playerId)?.gameProfile?.name

    override fun isAdmin(playerId: UUID): Boolean {
        val player = server?.playerList?.getPlayer(playerId) ?: return false
        return ServerPacketHandler.isOpLevel2(player)
    }
}
