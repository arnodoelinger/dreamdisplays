package com.dreamdisplays.server.utils.net

import com.dreamdisplays.protocol.ClientHello
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which connected players negotiated protocol v2 (by sending a [ClientHello]). Players
 * absent from the map stay on the frozen v1 protocol. Platform-neutral; both the Paper and the
 * Fabric flavor share it.
 */
object V2PlayerTracker {
    private val players = ConcurrentHashMap<UUID, ClientHello>()

    /** Marks [uuid] as a v2 peer and remembers its advertised capabilities. */
    fun markV2(uuid: UUID, hello: ClientHello) {
        players[uuid] = hello
    }

    /** True if [uuid] completed the v2 hello; such players receive v2 packets only. */
    fun isV2(uuid: UUID): Boolean = players.containsKey(uuid)

    /** The capabilities [uuid] advertised, or null for v1 peers. */
    fun helloOf(uuid: UUID): ClientHello? = players[uuid]

    /** Drops the per-player state on disconnect. */
    fun clear(uuid: UUID) {
        players.remove(uuid)
    }
}
