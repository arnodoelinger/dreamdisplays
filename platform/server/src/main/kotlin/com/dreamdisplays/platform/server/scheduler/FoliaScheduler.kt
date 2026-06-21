package com.dreamdisplays.platform.server.scheduler

import io.github.arsmotorin.ofrat.PaperOnly

import org.bukkit.plugin.Plugin
import org.jspecify.annotations.NullMarked
import java.util.concurrent.TimeUnit

/**
 * `Folia` scheduler adapter. The sync task runs on the global scheduler and must only coordinate
 * region/entity work; player/world access belongs in per-player or per-region tasks.
 */
@PaperOnly @NullMarked object FoliaScheduler : AdapterScheduler {
    private const val TICK_MILLIS = 50L

    /**
     * Schedules [task] through `Folia`'s global scheduler. Callers must dispatch entity/region
     * operations from this coordinator before touching players, locations, worlds or chunks.
     */
    override fun runRepeatingSync(
        plugin: Plugin,
        delayTicks: Long,
        intervalTicks: Long,
        task: Runnable,
    ) {
        plugin.server.globalRegionScheduler.runAtFixedRate(
            plugin,
            { task.run() },
            delayTicks.coerceAtLeast(1L),
            intervalTicks.coerceAtLeast(1L),
        )
    }

    /** Schedules [task] through `Folia`'s async scheduler. */
    override fun runRepeatingAsync(
        plugin: Plugin,
        delayTicks: Long,
        intervalTicks: Long,
        task: Runnable,
    ) {
        plugin.server.asyncScheduler.runAtFixedRate(
            plugin,
            { task.run() },
            delayTicks.coerceAtLeast(0L) * TICK_MILLIS,
            intervalTicks.coerceAtLeast(1L) * TICK_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }
}
