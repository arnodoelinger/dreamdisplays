package com.dreamdisplays.platform.server.meta

import kotlinx.coroutines.*

/**
 * Server-side coroutine scope for pure off-thread IO that never touches `Bukkit` / `Minecraft`
 * state — webhook POSTs, JDBC writes, the `Fabric` update poll, and delayed marshal-to-main tasks.
 *
 * Anything that touches the world, entities, or players still goes through [Scheduler] (`Paper` async /
 * region schedulers) or `server.execute`; this scope only does the IO and hands results back there.
 * Backed by the daemon [Dispatchers.IO] pool, so it never blocks JVM shutdown; [shutdown] cancels it
 * on a clean server stop. A [SupervisorJob] keeps one failed task from cancelling the rest.
 */
object ServerCoroutines {
    /** The server-side coroutine scope for all background IO. */
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("DD-Server-IO"))

    /** Cancels all background server IO coroutines. Called on server stop / plugin disable. */
    fun shutdown() {
        io.cancel()
    }
}
