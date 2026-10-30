package com.dreamdisplays.shared.storage

import java.util.*

/** In-memory registry of server-authoritative display snapshots, keyed by server ID. */
interface StorageService {
    /** Replaces the registry for [serverId] with [displays] and marks it as the current server. */
    fun load(serverId: String, displays: Map<UUID, DisplayData> = emptyMap())

    /** @return an immutable copy of every display registered for [serverId]. */
    fun snapshot(serverId: String): Map<UUID, DisplayData>

    /** @return the server ID set by the most recent [load] call, or `null` if none. */
    fun currentServerId(): String?

    /** @return the cached [DisplayData] for [displayUuid] on the current server, or `null` if absent. */
    fun getDisplayData(displayUuid: UUID): DisplayData?

    /** Stores [data] for [displayUuid] in the current server's registry. */
    fun saveDisplayData(displayUuid: UUID, data: DisplayData)

    /**
     * Removes [displayUuid] from every server registry.
     *
     * @return whether anything was removed.
     */
    fun removeDisplay(displayUuid: UUID): Boolean
}
