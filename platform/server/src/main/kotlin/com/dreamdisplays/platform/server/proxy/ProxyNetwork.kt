package com.dreamdisplays.platform.server.proxy

import com.dreamdisplays.core.protocol.proxy.NetworkSessionInfo
import java.util.concurrent.atomic.AtomicReference

/**
 * Backend-side view of the proxy network, filled in by [ProxyBridge] when a
 * [com.dreamdisplays.core.protocol.proxy.ProxyWelcome] / `NetworkSessionList` arrives. Platform-neutral.
 */
object ProxyNetwork {
    private val ownName = AtomicReference<String?>(null)
    private val names = AtomicReference<Set<String>>(emptySet())

    /** Last `NetworkSessionList` the proxy sent, for `/display fullscreen list` to merge in. */
    private val lastSessions = AtomicReference<List<NetworkSessionInfo>>(emptyList())

    /** This backend's own name, as assigned by the proxy — null until a [ProxyWelcome] arrives. */
    fun ownServerName(): String? = ownName.get()

    /** Every backend name currently known to the proxy, refreshed on each `ProxyWelcome`. */
    fun serverNames(): Set<String> = names.get()

    /** Called by [ProxyBridge] on receiving a `ProxyWelcome`. */
    fun update(yourServerName: String, allServerNames: List<String>) {
        ownName.set(yourServerName)
        names.set(allServerNames.toSet())
    }

    /** Called by [ProxyBridge] on receiving a `NetworkSessionList`. */
    fun updateSessions(sessions: List<NetworkSessionInfo>) {
        lastSessions.set(sessions)
    }

    /** The most recently received network fullscreen roster (may be stale by up to one request round trip). */
    fun networkSessions(): List<NetworkSessionInfo> = lastSessions.get()

    /** Whether this backend has ever heard back from a proxy. */
    fun isConnected(): Boolean = ownName.get() != null
}
