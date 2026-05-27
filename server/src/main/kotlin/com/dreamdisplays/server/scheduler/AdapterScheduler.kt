package com.dreamdisplays.server.scheduler

import io.github.arsmotorin.ofrat.PaperOnly

import org.bukkit.plugin.Plugin
import org.jspecify.annotations.NullMarked

/**
 * An adapter interface for scheduling asynchronous tasks for different server implementations.
 */
@PaperOnly @NullMarked interface AdapterScheduler {
    /** Schedules [task] to run repeatedly on an async thread with the given delay and interval (in ticks). */
    fun runRepeatingAsync(plugin: Plugin, delayTicks: Long, intervalTicks: Long, task: Runnable)
}
