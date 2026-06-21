package com.dreamdisplays.core.storage


import java.util.UUID

/**
 * In-memory registry for server-authoritative display snapshots.
 */
object DisplayStorage {
    private val serverDisplays = HashMap<String, MutableMap<UUID, FullDisplayData>>()
    private var currentServerId: String? = null

    fun load(serverId: String, displays: Map<UUID, FullDisplayData> = emptyMap()) {
        currentServerId = serverId
        serverDisplays[serverId] = displays.toMutableMap()
    }

    fun snapshot(serverId: String): Map<UUID, FullDisplayData> =
        serverDisplays[serverId]?.toMap() ?: emptyMap()

    fun currentServerId(): String? = currentServerId

    fun getDisplayData(displayUuid: UUID): FullDisplayData? {
        val serverId = currentServerId ?: return null
        return serverDisplays[serverId]?.get(displayUuid)
    }

    fun saveDisplayData(displayUuid: UUID, data: FullDisplayData) {
        val serverId = currentServerId ?: return
        serverDisplays.getOrPut(serverId) { HashMap() }[displayUuid] = data
    }

    fun removeDisplay(displayUuid: UUID): Boolean {
        var removed = false
        for (displays in serverDisplays.values) {
            removed = displays.remove(displayUuid) != null || removed
        }
        return removed
    }
}
