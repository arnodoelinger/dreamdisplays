package com.dreamdisplays.core.storage

import com.dreamdisplays.api.storage.FullDisplayData
import java.util.*
import com.dreamdisplays.api.storage.DisplayStorage as DisplayStorageContract

/**
 * In-memory registry for server-authoritative display snapshots.
 */
object DisplayStorage : DisplayStorageContract {
    private val serverDisplays = HashMap<String, MutableMap<UUID, FullDisplayData>>()
    private var currentServerId: String? = null

    override fun load(serverId: String, displays: Map<UUID, FullDisplayData>) {
        currentServerId = serverId
        serverDisplays[serverId] = displays.toMutableMap()
    }

    override fun snapshot(serverId: String): Map<UUID, FullDisplayData> =
        serverDisplays[serverId]?.toMap() ?: emptyMap()

    override fun currentServerId(): String? = currentServerId

    override fun getDisplayData(displayUuid: UUID): FullDisplayData? {
        val serverId = currentServerId ?: return null
        return serverDisplays[serverId]?.get(displayUuid)
    }

    override fun saveDisplayData(displayUuid: UUID, data: FullDisplayData) {
        val serverId = currentServerId ?: return
        serverDisplays.getOrPut(serverId) { HashMap() }[displayUuid] = data
    }

    override fun removeDisplay(displayUuid: UUID): Boolean {
        var removed = false
        for (displays in serverDisplays.values) {
            removed = displays.remove(displayUuid) != null || removed
        }
        return removed
    }
}
