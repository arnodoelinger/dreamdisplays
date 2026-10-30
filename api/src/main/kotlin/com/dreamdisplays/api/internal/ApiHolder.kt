package com.dreamdisplays.api.internal

/**
 * Holds the live [ApiBackend]. Written by `Dream Displays` itself.
 *
 * @since 1.10.x
 */
object ApiHolder {
    private val waiting = ArrayList<(ApiBackend) -> Unit>()

    /** The current API, or `null` while `Dream Displays` is not loaded. */
    @Volatile
    var current: ApiBackend? = null; private set

    /** Publishes [api] and runs everything that was waiting for it. */
    fun attach(api: ApiBackend) {
        val pending = synchronized(waiting) {
            current = api
            waiting.toList().also { waiting.clear() }
        }
        pending.forEach { it(api) }
    }

    /** Withdraws the API after shutdown. */
    fun detach() {
        current = null
    }

    /** Runs [action] now if the API is available, otherwise queues it until [attach]. */
    fun whenReady(action: (ApiBackend) -> Unit) {
        val api = synchronized(waiting) {
            current ?: run { waiting += action; null }
        }
        if (api != null) action(api)
    }
}
