package com.dreamdisplays.platform.proxy

import com.dreamdisplays.core.protocol.proxy.packets.BackendDisplayIndex
import java.util.concurrent.ConcurrentHashMap

/**
 * What every backend can play, as last advertised by each of them. Exists because plugin messages ride player connections,
 * so the proxy needs its own cross-backend view.
 */
object NetworkDisplayIndex {
    /** Backend name -> (display id -> url). */
    private val byServer = ConcurrentHashMap<String, Map<String, String>>()

    /** Replaces [serverName]'s advertised displays with [index]. */
    fun update(serverName: String, index: BackendDisplayIndex) {
        byServer[serverName] = index.displays
            .filter { it.id.isNotBlank() && it.url.isNotBlank() }
            .associate { it.id.lowercase() to it.url }
    }

    /** Forgets everything [serverName] advertised. */
    fun forget(serverName: String) {
        byServer.remove(serverName)
    }

    /**
     * The url of the display [token] names anywhere in the network — a full id, or the 8-character
     * short id `/display list` shows. An ambiguous prefix resolves to nothing rather than guessing.
     */
    fun resolve(token: String): String? {
        val needle = token.lowercase()
        val all = byServer.values.flatMap { it.entries }
        all.firstOrNull { it.key == needle }?.let { return it.value }
        if (needle.length < 4) return null
        val matches = all.filter { it.key.startsWith(needle) }.map { it.value }.distinct()
        return matches.singleOrNull()
    }
}
