package com.dreamdisplays.platform.server.managers

import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches which players are near which displays for `Folia`, whose global region coordinators
 * cannot read entity locations directly. Populated per-player by each player's own entity
 * scheduler task, then read back by the global coordinator in [DisplayManager].
 */
internal class DisplayProximityIndex {
    /** Cached nearby player ids for each display id. */
    private val nearbyPlayersByDisplay: MutableMap<UUID, MutableSet<UUID>> = ConcurrentHashMap()

    /** Cached nearby display ids for each player id. */
    private val nearbyDisplaysByPlayer: MutableMap<UUID, Set<UUID>> = ConcurrentHashMap()

    /** Removes a display from the cached proximity index. */
    fun forgetDisplay(displayId: UUID) {
        nearbyPlayersByDisplay.remove(displayId)
        nearbyDisplaysByPlayer.replaceAll { _, ids -> ids - displayId }
    }

    /** Removes [playerId] from the cached proximity index. */
    fun forgetPlayer(playerId: UUID) {
        nearbyDisplaysByPlayer.remove(playerId)?.forEach { displayId ->
            nearbyPlayersByDisplay[displayId]?.remove(playerId)
        }
    }

    /** Cached nearby player ids for [displayId], safe to read from the global coordinator. */
    fun trackedNearbyPlayerIds(displayId: UUID): List<UUID> =
        nearbyPlayersByDisplay[displayId]?.toList() ?: emptyList()

    /** Updates the cached index after [playerId]'s entity task computed their nearby displays. */
    fun update(playerId: UUID, nearbyDisplayIds: Set<UUID>) {
        val previous = nearbyDisplaysByPlayer.put(playerId, nearbyDisplayIds) ?: emptySet()

        (previous - nearbyDisplayIds).forEach { displayId ->
            nearbyPlayersByDisplay[displayId]?.remove(playerId)
        }
        (nearbyDisplayIds - previous).forEach { displayId ->
            nearbyPlayersByDisplay.computeIfAbsent(displayId) { ConcurrentHashMap.newKeySet() }.add(playerId)
        }
    }
}
