package com.dreamdisplays.platform.proxy

import java.util.concurrent.ConcurrentHashMap

/**
 * Proxy-side collection window for a [com.dreamdisplays.core.protocol.proxy.ResolveDisplayToken]
 * fan-out. More than one backend can plausibly answer the same short token — different backends'
 * display ids are independent id spaces, so a short prefix that's unambiguous on each individual
 * backend can still collide across the network.
 */
object NetworkTokenResolutions {
    /** How long a fan-out waits for backend replies before [settle] decides. */
    const val FANOUT_WINDOW_MS: Long = 500L

    private class Pending(val originServer: String) {
        val urls: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Opens a collection window for [requestId], fanned out on behalf of [originServer]. */
    fun start(requestId: String, originServer: String) {
        pending[requestId] = Pending(originServer)
    }

    /** Buffers one backend's answer. A false return means [requestId]'s window already closed — the reply arrived too late and is dropped. */
    fun addReply(requestId: String, url: String): Boolean {
        val entry = pending[requestId] ?: return false
        entry.urls.add(url)
        return true
    }

    /**
     * Closes [requestId]'s window. Returns the origin server name paired with the single url to
     * forward it, or null when nothing replied in time, more than one backend disagreed (ambiguous),
     * or the window was already closed (e.g. by a previous call).
     */
    fun settle(requestId: String): Pair<String, String>? {
        val entry = pending.remove(requestId) ?: return null
        return entry.urls.singleOrNull()?.let { entry.originServer to it }
    }
}
