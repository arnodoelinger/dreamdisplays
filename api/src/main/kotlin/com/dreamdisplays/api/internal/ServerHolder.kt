package com.dreamdisplays.api.internal

/**
 * Holds the live [ServerBackend]. Written by the server plugin itself.
 *
 * @since 1.10.x
 */
object ServerHolder {
    private val waiting = ArrayList<(ServerBackend) -> Unit>()

    @Volatile
    var current: ServerBackend? = null; private set

    /** Publishes [backend] and runs everything that was waiting for it. */
    fun attach(backend: ServerBackend) {
        val pending = synchronized(waiting) {
            current = backend
            waiting.toList().also { waiting.clear() }
        }
        pending.forEach { it(backend) }
    }

    /** Withdraws the backend after shutdown. */
    fun detach() {
        current = null
    }

    /** Runs [action] now if the backend is available, otherwise queues it until [attach]. */
    fun whenReady(action: (ServerBackend) -> Unit) {
        val backend = synchronized(waiting) {
            current ?: run { waiting += action; null }
        }
        if (backend != null) action(backend)
    }
}
