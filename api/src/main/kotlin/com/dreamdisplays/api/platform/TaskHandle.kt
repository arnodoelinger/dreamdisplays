package com.dreamdisplays.api.platform

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/** Cancellable handle returned by scheduled platform tasks. */
@DreamDisplaysUnstableApi
fun interface TaskHandle {
    /** Cancels future executions when the platform scheduler supports cancellation. */
    fun cancel()

    companion object {
        /** Handle for work that cannot or does not need to be cancelled. */
        val NOOP: TaskHandle = TaskHandle { }
    }
}
