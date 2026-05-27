package com.dreamdisplays.server.managers

import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.server.datatypes.FabricDisplayData
import com.dreamdisplays.server.datatypes.PaperDisplayData
import com.dreamdisplays.server.datatypes.StateData
import com.dreamdisplays.server.datatypes.SyncData
import com.dreamdisplays.server.managers.DisplayManager.getDisplayData
import com.dreamdisplays.server.managers.DisplayManager.getReceivers
import com.dreamdisplays.server.utils.net.FabricPacketUtil
import com.dreamdisplays.server.utils.net.PacketUtil
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages server-side playback state for synced displays. Processes sync packets from clients,
 * rate-limits rebroadcasts, and periodically pushes the authoritative position to keep
 * all viewers in lockstep.
 */
@NullMarked object StateManager {
    private val playStates: MutableMap<UUID, StateData> = ConcurrentHashMap()
    private val lastSyncBroadcast: MutableMap<UUID, Long> = ConcurrentHashMap()
    private const val SYNC_MIN_INTERVAL_MS = 250L
    private const val PERIODIC_BROADCAST_INTERVAL_MS = 2000L


    /**
     * Handles a sync packet from [player]: validates it, updates the per-display state,
     * and rebroadcasts to other receivers (rate-limited to avoid packet floods).
     */
    @PaperOnly @JvmStatic fun processSyncPacket(packet: SyncData, player: Player) {
        val displayId = packet.id ?: return
        val data = getDisplayData(displayId)
        if (data != null) data.isSync = packet.isSync

        if (!packet.isSync) {
            playStates.remove(displayId)
            lastSyncBroadcast.remove(displayId)
            return
        }

        if (data == null) {
            playStates.remove(displayId)
            return
        }

        if (data.isLocked && data.ownerId != player.uniqueId) return

        if (packet.currentTime < 0 || packet.limitTime < 0
            || packet.currentTime > 24L * 60 * 60 * 1_000_000_000L
        ) return

        val state = playStates.computeIfAbsent(displayId) { id -> StateData(id) }
        state.update(packet)
        data.duration = packet.limitTime

        val now = System.currentTimeMillis()
        val lastBroadcast = lastSyncBroadcast[displayId] ?: 0L
        if (now - lastBroadcast < SYNC_MIN_INTERVAL_MS) return
        lastSyncBroadcast[displayId] = now

        val receivers = getReceivers(data as PaperDisplayData)

        PacketUtil.sendSync(
            receivers.filter { it.uniqueId != player.uniqueId }.toMutableList(),
            packet.copy(id = displayId)
        )
    }

    /**
     * Handles a sync packet from [player]: validates it, updates the per-display state,
     * and rebroadcasts to other receivers (rate-limited to avoid packet floods).
     */
    @FabricOnly fun processSyncPacket(packet: SyncData, player: ServerPlayer, server: MinecraftServer) {
        val displayId = packet.id ?: return
        val data = getDisplayData(displayId)
        if (data != null) data.isSync = packet.isSync

        if (!packet.isSync) {
            playStates.remove(displayId)
            lastSyncBroadcast.remove(displayId)
            return
        }

        if (data == null) {
            playStates.remove(displayId)
            return
        }

        if (data.isLocked && data.ownerId != player.uuid) return

        if (packet.currentTime < 0 || packet.limitTime < 0
            || packet.currentTime > 24L * 60 * 60 * 1_000_000_000L
        ) return

        val state = playStates.computeIfAbsent(displayId) { id -> StateData(id) }
        state.update(packet)
        data.duration = packet.limitTime

        val now = System.currentTimeMillis()
        val lastBroadcast = lastSyncBroadcast[displayId] ?: 0L
        if (now - lastBroadcast < SYNC_MIN_INTERVAL_MS) return
        lastSyncBroadcast[displayId] = now

        val receivers = getReceivers(data as FabricDisplayData, server)
            .filter { it.uuid != player.uuid }

        FabricPacketUtil.sendSync(receivers, packet.copy(id = displayId))
    }

    /** Sends the current sync packet for display [id] to a single [player], if state exists. */
    @PaperOnly @JvmStatic fun sendSyncPacket(id: UUID?, player: Player?) {
        val displayId = id ?: return
        val state = playStates[displayId] ?: return

        val packet = state.createPacket()
        PacketUtil.sendSync(mutableListOf(player), packet)
    }

    /** Sends the current sync packet for display [id] to a single [player], if state exists. */
    @FabricOnly fun sendSyncPacket(id: UUID?, player: ServerPlayer) {
        val displayId = id ?: return
        val state = playStates[displayId] ?: return
        val display = getDisplayData(displayId) as? FabricDisplayData
        val packet = state.createPacket(display)
        FabricPacketUtil.sendSync(listOf(player), packet)
    }

    /** Resets the server-side clock for [displayId] to 0 (called when owner switches video). */
    @PaperOnly @JvmStatic fun resetAndBroadcast(displayId: UUID, receivers: List<Player>) {
        val display = getDisplayData(displayId) ?: return
        val state = playStates.computeIfAbsent(displayId) { id -> StateData(id) }
        state.update(SyncData(displayId, true, false, 0L, 0L))
        display.duration = 0L
        lastSyncBroadcast[displayId] = System.currentTimeMillis()
        PacketUtil.sendSync(receivers.toMutableList(), state.createPacket())
    }

    /** Resets the server-side clock for [displayId] to 0 (called when owner switches video). */
    @FabricOnly fun resetAndBroadcast(displayId: UUID, receivers: List<ServerPlayer>) {
        val display = getDisplayData(displayId) as? FabricDisplayData ?: return
        val state = playStates.computeIfAbsent(displayId) { id -> StateData(id) }
        state.update(SyncData(displayId, true, false, 0L, 0L))
        display.duration = 0L
        lastSyncBroadcast[displayId] = System.currentTimeMillis()
        FabricPacketUtil.sendSync(receivers, state.createPacket(display))
    }

    /**
     * Periodically broadcasts the current sync packet for every active sync display to keep
     * clients in lockstep. Without this, clients drift after the initial sync.
     */
    @PaperOnly @JvmStatic fun tickBroadcast() {
        if (playStates.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((displayId, state) in playStates) {
            val last = lastSyncBroadcast[displayId] ?: 0L
            if (now - last < PERIODIC_BROADCAST_INTERVAL_MS) continue
            val display = getDisplayData(displayId) ?: continue
            if (!display.isSync) continue
            lastSyncBroadcast[displayId] = now
            val receivers = getReceivers(display as PaperDisplayData)
            if (receivers.isNotEmpty()) PacketUtil.sendSync(receivers.toMutableList(), state.createPacket())
        }
    }

    /**
     * Periodically broadcasts the current sync packet for every active sync display to keep
     * clients in lockstep. Without this, clients drift after the initial sync.
     */
    @FabricOnly fun tickBroadcast(server: MinecraftServer) {
        if (playStates.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((displayId, state) in playStates) {
            val last = lastSyncBroadcast[displayId] ?: 0L
            if (now - last < PERIODIC_BROADCAST_INTERVAL_MS) continue
            val display = getDisplayData(displayId) as? FabricDisplayData ?: continue
            if (!display.isSync) continue
            lastSyncBroadcast[displayId] = now
            val receivers = getReceivers(display, server)
            if (receivers.isNotEmpty()) FabricPacketUtil.sendSync(receivers, state.createPacket(display))
        }
    }
}
