package com.dreamdisplays.server.managers

import com.dreamdisplays.server.datatypes.StateData
import com.dreamdisplays.server.datatypes.SyncData
import com.dreamdisplays.server.utils.net.PacketUtil
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages sync (playback state) for displays server-side.
 *
 * `Fabric server` implementation.
 */
object StateManager {
    // Paper start
    private val playStates: MutableMap<UUID, StateData> = ConcurrentHashMap()
    private val lastSyncBroadcast: MutableMap<UUID, Long> = ConcurrentHashMap()

    private const val SYNC_MIN_INTERVAL_MS = 250L

    fun processSyncPacket(packet: SyncData, player: ServerPlayer, server: MinecraftServer) {
        val displayId = packet.id ?: return
        val data = DisplayManager.getDisplayData(displayId)
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

        // Fabric server start
        if (data.isLocked && data.ownerId != player.uuid) return
        // Fabric server end

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

        // Fabric server start
        val receivers = DisplayManager.getReceivers(data, server)
            .filter { it.uuid != player.uuid }
        // Fabric server end

        PacketUtil.sendSync(receivers, packet.copy(id = displayId))
    }

    fun sendSyncPacket(id: UUID?, player: ServerPlayer) {
        val displayId = id ?: return
        val state = playStates[displayId] ?: return
        val display = DisplayManager.getDisplayData(displayId)
        val packet = state.createPacket(display)
        PacketUtil.sendSync(listOf(player), packet)
        // Paper end
    }

    // Fabric server start
    /** Resets the server-side clock for [displayId] to 0 (called when owner switches video). */
    fun resetAndBroadcast(displayId: UUID, receivers: List<ServerPlayer>) {
        val display = DisplayManager.getDisplayData(displayId) ?: return
        val state = playStates.computeIfAbsent(displayId) { id -> StateData(id) }
        state.update(SyncData(displayId, true, false, 0L, 0L))
        display.duration = 0L
        lastSyncBroadcast[displayId] = System.currentTimeMillis()
        PacketUtil.sendSync(receivers, state.createPacket(display))
    }

    /**
     * Periodically broadcasts the current sync packet for every active sync display to keep
     * clients in lockstep. Without this, clients drift after the initial sync.
     */
    fun tickBroadcast(server: MinecraftServer) {
        if (playStates.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((displayId, state) in playStates) {
            val last = lastSyncBroadcast[displayId] ?: 0L
            if (now - last < PERIODIC_BROADCAST_INTERVAL_MS) continue
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            if (!display.isSync) continue
            lastSyncBroadcast[displayId] = now
            val receivers = DisplayManager.getReceivers(display, server)
            if (receivers.isNotEmpty()) PacketUtil.sendSync(receivers, state.createPacket(display))
        }
    }

    private const val PERIODIC_BROADCAST_INTERVAL_MS = 2000L
    // Fabric server end
}
