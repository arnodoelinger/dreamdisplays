package com.dreamdisplays.api.platform.capability

/**
 * Cancellable handle returned by scheduled platform tasks.
 *
 * @since 1.8.x
 */
fun interface TaskHandle {
    /** Cancels future executions when the platform scheduler supports cancellation. */
    fun cancel()
}
