package com.dreamdisplays.platform.server.proxy

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks players the proxy has told this backend are mid-transfer to another backend, so their disconnect isn't mistaken
 * for a real leave.
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
