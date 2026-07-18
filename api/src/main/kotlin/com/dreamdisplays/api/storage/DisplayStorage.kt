package com.dreamdisplays.api.storage

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import java.util.*

/**
 * In-memory registry of server-authoritative display snapshots, keyed by server id.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
interface DisplayStorage {
    /** Replaces the registry for [serverId] with [displays] and marks it as the current server. */
    fun load(serverId: String, displays: Map<UUID, FullDisplayData> = emptyMap())

    /** Returns an immutable copy of every display registered for [serverId]. */
    fun snapshot(serverId: String): Map<UUID, FullDisplayData>

    /** Returns the server id set by the most recent [load] call, or null if none. */
    fun currentServerId(): String?

    /** Returns the cached [FullDisplayData] for [displayUuid] on the current server, or null if absent. */
    fun getDisplayData(displayUuid: UUID): FullDisplayData?

    /** Stores [data] for [displayUuid] in the current server's registry. */
    fun saveDisplayData(displayUuid: UUID, data: FullDisplayData)

    /** Removes [displayUuid] from every server registry. Returns whether anything was removed. */
    fun removeDisplay(displayUuid: UUID): Boolean
}
