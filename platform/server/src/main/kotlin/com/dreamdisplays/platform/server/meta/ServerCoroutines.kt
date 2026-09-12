package com.dreamdisplays.platform.server.meta

import kotlinx.coroutines.*

/**
 * Server-side coroutine scope for pure off-thread IO that never touches `Bukkit` / `Minecraft` state — webhooks, HTTP calls,
 * and similar.
 */
object ServerCoroutines {
    /** The server-side coroutine scope for all background IO. */
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("Server-IO"))

    /** Cancels all background server IO coroutines. Called on server stop / plugin disable. */
    fun shutdown() {
        io.cancel()
    }

    /**
     * Runs everything [shutdown] will, on a throwaway scope, so the coroutine classes it needs — the
     * scope's own, and the cancellation machinery, which nothing else here ever touches — are loaded
     * while the plugin classloader is fresh.
     */
    fun warmUp() {
        CoroutineScope(SupervisorJob()).cancel()
    }
}
