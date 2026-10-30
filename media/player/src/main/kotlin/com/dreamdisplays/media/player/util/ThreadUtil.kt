package com.dreamdisplays.media.player.util

/** Creates a daemon thread (non-runnable) with [name] that runs [r]. */
internal fun daemon(r: () -> Unit, name: String): Thread = Thread(r, name).apply { isDaemon = true }

/** Creates a daemon thread (runnable) with [name] that runs [r]. */
internal fun daemon(r: Runnable, name: String): Thread = Thread(r, name).apply { isDaemon = true }

/**
 * Joins the thread [t] with a timeout of 2 seconds, ignoring [InterruptedException] and interrupting the current thread instead.
 *
 * Safe to call from any thread, including [t] itself (in which case it does nothing).
 *
 * Used for joining media threads during shutdown without risking hangs from stuck threads.
 */
internal fun joinSafely(t: Thread?) {
    if (t != null && t != Thread.currentThread()) {
        runCatching { t.join(2000) }.onFailure { e ->
            if (e !is InterruptedException) throw e
            Thread.currentThread().interrupt()
        }
    }
}
