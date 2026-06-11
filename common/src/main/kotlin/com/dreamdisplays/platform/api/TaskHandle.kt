package com.dreamdisplays.platform.api

fun interface TaskHandle {
    fun cancel()

    companion object {
        val NOOP: TaskHandle = TaskHandle { }
    }
}
