package com.dreamdisplays.server.playback

import com.dreamdisplays.protocol.DreamPacket
import com.dreamdisplays.server.Main
import com.dreamdisplays.server.datatypes.DisplayData
import com.dreamdisplays.server.datatypes.PaperDisplayData
import com.dreamdisplays.server.managers.DisplayManager
import com.dreamdisplays.server.utils.net.PaperV2Networking
import com.dreamdisplays.server.utils.net.V2PlayerTracker
import io.github.arsmotorin.ofrat.PaperOnly
import org.bukkit.Bukkit
import org.jspecify.annotations.NullMarked
import java.util.UUID

/** `Paper` implementation of [PlaybackTransport]: v2 envelopes via [PaperV2Networking]. */
@PaperOnly @NullMarked object PaperPlaybackTransport : PlaybackTransport {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun broadcast(display: DisplayData, packet: DreamPacket) {
        val paper = display as? PaperDisplayData ?: return
        val receivers = DisplayManager.getReceivers(paper).filter { V2PlayerTracker.isV2(it.uniqueId) }
        if (receivers.isNotEmpty()) PaperV2Networking.send(receivers, packet)
    }

    override fun sendTo(playerId: UUID, packet: DreamPacket) {
        val player = Bukkit.getPlayer(playerId) ?: return
        if (V2PlayerTracker.isV2(playerId)) PaperV2Networking.send(listOf(player), packet)
    }

    override fun nearbyPlayerIds(display: DisplayData): List<UUID> {
        val paper = display as? PaperDisplayData ?: return emptyList()
        return DisplayManager.getReceivers(paper).map { it.uniqueId }
    }

    override fun playerName(playerId: UUID): String? = Bukkit.getPlayer(playerId)?.name

    override fun isAdmin(playerId: UUID): Boolean =
        Bukkit.getPlayer(playerId)?.hasPermission(Main.config.permissions.delete) == true
}
