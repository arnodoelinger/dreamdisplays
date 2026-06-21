package com.dreamdisplays.api.platform

interface PlatformScheduler {
    val isOnMainThread: Boolean

    fun runOnMainThread(task: () -> Unit)
    fun runAsync(task: () -> Unit): TaskHandle
    fun runRepeating(intervalTicks: Long, task: () -> Unit): TaskHandle
    fun runDelayed(delayTicks: Long, task: () -> Unit): TaskHandle
}
