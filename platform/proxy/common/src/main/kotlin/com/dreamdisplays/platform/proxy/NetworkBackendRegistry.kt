package com.dreamdisplays.platform.proxy

import com.dreamdisplays.core.protocol.proxy.packets.BackendHello
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Proxy-side bookkeeping shared by both the `Velocity` and `BungeeCord` adapters: which backends in the network are
 * alive and what each last reported about itself.
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

    /** The full configured roster — used to answer [com.dreamdisplays.core.protocol.proxy.packets.ProxyWelcome.allServerNames]. */
    fun allServerNames(): Set<String> = known.get()

    /** Records that [serverName] announced itself with [hello] just now. */
    fun recordHello(serverName: String, hello: BackendHello, nowMs: Long) {
        seen[serverName] = BackendInfo(hello.pluginVersion, hello.mcVersion, hello.platform, nowMs)
    }

    /** Every backend that has announced itself at least once, by name. */
    fun snapshot(): Map<String, BackendInfo> = seen.toMap()
}
