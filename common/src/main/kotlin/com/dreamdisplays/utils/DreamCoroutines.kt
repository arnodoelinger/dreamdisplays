package com.dreamdisplays.utils

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Shared client-side coroutine scope for background work, replacing the per-subsystem
 * `java.util.concurrent` thread pools (yt-dlp fetch/search, thumbnails, disk caches, update checks).
 *
 * [clientIo] runs blocking IO (network, subprocess, disk) on the elastic [Dispatchers.IO] pool; a
 * [SupervisorJob] keeps one failed task from cancelling the rest. Coroutines launched here are
 * backed by daemon threads, so they never block JVM shutdown; [shutdown] cancels them on a clean exit.
 */
object DreamCoroutines {
    val clientIo: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("DD-IO"))

    /** Cancels all background client coroutines. Called on client shutdown. */
    fun shutdown() {
        clientIo.cancel()
    }
}
