package com.dreamdisplays.server.managers

import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.server.datatypes.DisplayData
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
     * Validates a sync [packet] sent by [senderId], updates the per-display state, and applies
     * the rebroadcast rate limit. Returns true when the caller should rebroadcast to other
     * receivers, false when the packet was rejected, cleared the state, or was throttled.
     */
    private fun applySyncPacket(packet: SyncData, senderId: UUID): Boolean {
        val displayId = packet.id ?: return false
        val data = getDisplayData(displayId)
        if (data != null) data.isSync = packet.isSync

        if (!packet.isSync) {
            playStates.remove(displayId)
            lastSyncBroadcast.remove(displayId)
            return false
        }

        if (data == null) {
            playStates.remove(displayId)
            return false
        }

        if (data.isLocked && data.ownerId != senderId) return false

        if (packet.currentTime < 0 || packet.limitTime < 0
            || packet.currentTime > 24L * 60 * 60 * 1_000_000_000L
        ) return false

        val state = playStates.computeIfAbsent(displayId) { id -> StateData(id) }
        state.update(packet)
        data.duration = packet.limitTime

        val now = System.currentTimeMillis()
        val lastBroadcast = lastSyncBroadcast[displayId] ?: 0L
        if (now - lastBroadcast < SYNC_MIN_INTERVAL_MS) return false
        lastSyncBroadcast[displayId] = now
        return true
    }

    /**
     * Handles a sync packet from [player]: validates it, updates the per-display state,
     * and rebroadcasts to other receivers (rate-limited to avoid packet floods).
     */
    @PaperOnly @JvmStatic fun processSyncPacket(packet: SyncData, player: Player) {
        if (!applySyncPacket(packet, player.uniqueId)) return
        val data = getDisplayData(packet.id) ?: return
        val receivers = getReceivers(data as PaperDisplayData)
        PacketUtil.sendSync(
            receivers.filter { it.uniqueId != player.uniqueId }.toMutableList(),
            packet.copy(id = packet.id)
        )
    }

    /**
     * Handles a sync packet from [player]: validates it, updates the per-display state,
     * and rebroadcasts to other receivers (rate-limited to avoid packet floods).
     */
    @FabricOnly fun processSyncPacket(packet: SyncData, player: ServerPlayer, server: MinecraftServer) {
        if (!applySyncPacket(packet, player.uuid)) return
        val data = getDisplayData(packet.id) ?: return
        val receivers = getReceivers(data as FabricDisplayData, server)
            .filter { it.uuid != player.uuid }
        FabricPacketUtil.sendSync(receivers, packet.copy(id = packet.id))
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

    /**
     * Resets the server-side clock for [displayId] to 0 and returns the freshly reset state,
     * or null if the display no longer exists.
     */
    private fun resetState(displayId: UUID): StateData? {
        val display = getDisplayData(displayId) ?: return null
        val state = playStates.computeIfAbsent(displayId) { id -> StateData(id) }
        state.update(SyncData(displayId, true, false, 0L, 0L))
        display.duration = 0L
        lastSyncBroadcast[displayId] = System.currentTimeMillis()
        return state
    }

    /** Resets the server-side clock for [displayId] to 0 (called when owner switches video). */
    @PaperOnly @JvmStatic fun resetAndBroadcast(displayId: UUID, receivers: List<Player>) {
        val state = resetState(displayId) ?: return
        PacketUtil.sendSync(receivers.toMutableList(), state.createPacket())
    }

    /** Resets the server-side clock for [displayId] to 0 (called when owner switches video). */
    @FabricOnly fun resetAndBroadcast(displayId: UUID, receivers: List<ServerPlayer>) {
        val state = resetState(displayId) ?: return
        val display = getDisplayData(displayId) as? FabricDisplayData
        FabricPacketUtil.sendSync(receivers, state.createPacket(display))
    }

    /**
     * Iterates every active sync display whose periodic broadcast interval has elapsed, marks it
     * as just-broadcast, and invokes [action] with the display's state and data. Centralizes the
     * rate-limit bookkeeping shared by the platform-specific tick handlers.
     */
    private inline fun forEachBroadcastDue(action: (StateData, DisplayData) -> Unit) {
        if (playStates.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((displayId, state) in playStates) {
            val last = lastSyncBroadcast[displayId] ?: 0L
            if (now - last < PERIODIC_BROADCAST_INTERVAL_MS) continue
            val display = getDisplayData(displayId) ?: continue
            if (!display.isSync) continue
            lastSyncBroadcast[displayId] = now
            action(state, display)
        }
    }

    /**
     * Periodically broadcasts the current sync packet for every active sync display to keep
     * clients in lockstep. Without this, clients drift after the initial sync.
     */
    @PaperOnly @JvmStatic fun tickBroadcast() = forEachBroadcastDue { state, display ->
        val receivers = getReceivers(display as PaperDisplayData)
        if (receivers.isNotEmpty()) PacketUtil.sendSync(receivers.toMutableList(), state.createPacket())
    }

    /**
     * Periodically broadcasts the current sync packet for every active sync display to keep
     * clients in lockstep. Without this, clients drift after the initial sync.
     */
    @FabricOnly fun tickBroadcast(server: MinecraftServer) = forEachBroadcastDue { state, display ->
        val fabricDisplay = display as FabricDisplayData
        val receivers = getReceivers(fabricDisplay, server)
        if (receivers.isNotEmpty()) FabricPacketUtil.sendSync(receivers, state.createPacket(fabricDisplay))
    }
}
