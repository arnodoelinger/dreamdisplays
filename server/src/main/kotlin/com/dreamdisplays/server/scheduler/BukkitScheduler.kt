package com.dreamdisplays.server.scheduler

import io.github.arsmotorin.ofrat.PaperOnly

import org.bukkit.plugin.Plugin
import org.jspecify.annotations.NullMarked

/**
 * `Bukkit` scheduler. Provides methods for running tasks asynchronously, synchronously, or with a delay.
 */
@PaperOnly @NullMarked object BukkitScheduler : AdapterScheduler {
    /** Schedules [task] on `Bukkit`'s async pool with the given delay and interval (in ticks). */
    override fun runRepeatingAsync(plugin: Plugin, delayTicks: Long, intervalTicks: Long, task: Runnable) {
        plugin.server.scheduler.runTaskTimerAsynchronously(plugin, task, delayTicks, intervalTicks)
    }
}
