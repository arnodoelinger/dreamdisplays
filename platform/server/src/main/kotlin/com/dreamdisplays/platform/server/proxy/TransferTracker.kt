package com.dreamdisplays.platform.server.proxy

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks players the proxy has told this backend are mid-transfer to another backend (via
 * [com.dreamdisplays.core.protocol.proxy.PlayerTransferring]), so `PlayerQuitEvent`-driven cleanup
 * that assumes "they left" — currently [com.dreamdisplays.platform.server.playback.WatchPartyManager]'s
 * host-disconnect grace timer - can tell a proxy switch apart from a real quit.
 *
 * Entries expire on their own after [TTL_MS] so a transfer that never completes (player disconnected
 * mid-switch, or the [com.dreamdisplays.core.protocol.proxy.PlayerLeftNetwork] correction never
 * arrives) can't mask a later real quit forever. Platform-neutral: no Bukkit/Minecraft types.
 */
object TransferTracker {
    private const val TTL_MS = 60_000L

    private val transferring = ConcurrentHashMap<UUID, Long>()

    /** Marks [playerId] as transferring away from this backend, effective for [TTL_MS]. */
    fun markTransferring(playerId: UUID) {
        transferring[playerId] = System.currentTimeMillis() + TTL_MS
    }

    /** Whether [playerId] is currently marked transferring (and that mark hasn't expired). */
    fun isTransferring(playerId: UUID): Boolean {
        val expiresAt = transferring[playerId] ?: return false
        if (System.currentTimeMillis() >= expiresAt) {
            transferring.remove(playerId)
            return false
        }
        return true
    }

    /** Clears any transfer mark for [playerId] — called when the proxy confirms a real network-wide quit. */
    fun clear(playerId: UUID) {
        transferring.remove(playerId)
    }
}
