package com.dreamdisplays.api.platform.capability

import com.dreamdisplays.api.Unstable

/**
 * Cancellable handle returned by scheduled platform tasks.
 *
 * @since 1.8.x
 */
@Unstable
fun interface TaskHandle {
    /** Cancels future executions when the platform scheduler supports cancellation. */
    fun cancel()
}
