package com.dreamdisplays.platform.server.scheduler

import io.github.arsmotorin.ofrat.PaperOnly

import org.bukkit.plugin.Plugin
import org.jspecify.annotations.NullMarked
import java.util.concurrent.TimeUnit

/**
 * `Paper` scheduler adapter for regular, non-`Folia` servers.
 */
@PaperOnly @NullMarked object PaperScheduler : AdapterScheduler {
    /** Tick delay in milliseconds. */
    private const val TICK_MILLIS = 50L

    /** Schedules [task] on the primary server thread. */
    override fun runRepeatingSync(plugin: Plugin, delayTicks: Long, intervalTicks: Long, task: Runnable) {
        plugin.server.scheduler.runTaskTimer(plugin, task, delayTicks, intervalTicks)
    }

    /** Schedules [task] on `Paper`'s async scheduler with the given delay and interval (in ticks). */
    override fun runRepeatingAsync(plugin: Plugin, delayTicks: Long, intervalTicks: Long, task: Runnable) {
        plugin.server.asyncScheduler.runAtFixedRate(
            plugin,
            { task.run() },
            delayTicks.coerceAtLeast(0L) * TICK_MILLIS,
            intervalTicks.coerceAtLeast(1L) * TICK_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }
}
