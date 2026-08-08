package com.dreamdisplays.platform.proxy

import com.dreamdisplays.core.protocol.proxy.BackendDisplayIndex
import java.util.concurrent.ConcurrentHashMap

/**
 * What every backend can play, as last advertised by each of them.
 *
 * Exists because plugin messages ride player connections: a backend with nobody on it can neither be
 * asked what it hosts nor answer, so `fullscreen start id <id> server ...` for a display living on a
 * currently empty server had no way to resolve. Remembering each backend's index from when it did
 * have players makes that lookup instant and independent of who is online right now.
 *
 * Entries are replaced wholesale per backend and deliberately outlive that backend going empty; a
 * stale url is corrected the moment anyone joins it again.
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
