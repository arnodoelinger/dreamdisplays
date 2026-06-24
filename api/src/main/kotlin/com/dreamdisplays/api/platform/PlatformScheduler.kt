package com.dreamdisplays.api.platform

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Scheduler abstraction for crossing between platform main-thread work and background tasks.
 *
 * @since 1.8.0
 */
@DreamDisplaysUnstableApi
interface PlatformScheduler {
    /** True when the caller is already running on the platform main thread. */
    val isOnMainThread: Boolean

    /** Runs [task] on the platform main thread. */
    fun runOnMainThread(task: () -> Unit)

    /** Runs [task] asynchronously and returns a handle that can cancel it when supported. */
    fun runAsync(task: () -> Unit): TaskHandle

    /** Runs [task] every [intervalTicks] server / client ticks. */
    fun runRepeating(intervalTicks: Long, task: () -> Unit): TaskHandle

    /** Runs [task] once after [delayTicks] server / client ticks. */
    fun runDelayed(delayTicks: Long, task: () -> Unit): TaskHandle
}
