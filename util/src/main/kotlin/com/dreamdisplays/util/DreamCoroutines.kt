package com.dreamdisplays.util

import kotlinx.coroutines.*

/** Shared client-side coroutine scope for background work. */
object DreamCoroutines {
    val clientIo: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("Client-IO"))

    fun shutdown() {
        clientIo.cancel()
    }
}
