package com.dreamdisplays.api.platform

fun interface TaskHandle {
    fun cancel()

    companion object {
        val NOOP: TaskHandle = TaskHandle { }
    }
}
