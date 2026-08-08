package com.dreamdisplays.platform.proxy

import com.dreamdisplays.core.protocol.proxy.BackendHello
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Proxy-side bookkeeping shared by both the `Velocity` and `BungeeCord` adapters: which backends in
 * the network are configured, and which of them have announced themselves with a [BackendHello] so
 * far (a backend only does so once a player is actually connected to ride the plugin message on -
 * see `ProxyBridge` on the backend side, which explains why this is a "seen at least once" set, not
 * a liveness check).
 *
 * Deliberately platform-API-free so it compiles unchanged into both `dreamdisplays-velocity` and
 * `dreamdisplays-bungee` - the `Velocity` / `Bungeecord`-specific plugin classes own the actual player /
 * server-connection objects and only hand this registry plain strings.
 */
object NetworkBackendRegistry {
    /** What a backend told us about itself in its [BackendHello]. */
    data class BackendInfo(
        val pluginVersion: String,
        val mcVersion: String,
        val platform: String,
        val lastHelloMs: Long,
    )

    private val known = AtomicReference<Set<String>>(emptySet())
    private val seen = ConcurrentHashMap<String, BackendInfo>()

    /** Replaces the full roster of backend server names configured on the proxy (e.g. on reload). */
    fun updateKnownServers(names: Collection<String>) {
        known.set(names.toSet())
    }

    /** The full configured roster — used to answer [com.dreamdisplays.core.protocol.proxy.ProxyWelcome.allServerNames]. */
    fun allServerNames(): Set<String> = known.get()

    /** Records that [serverName] announced itself with [hello] just now. */
    fun recordHello(serverName: String, hello: BackendHello, nowMs: Long) {
        seen[serverName] = BackendInfo(hello.pluginVersion, hello.mcVersion, hello.platform, nowMs)
    }

    /** Every backend that has announced itself at least once, by name. */
    fun snapshot(): Map<String, BackendInfo> = seen.toMap()
}
