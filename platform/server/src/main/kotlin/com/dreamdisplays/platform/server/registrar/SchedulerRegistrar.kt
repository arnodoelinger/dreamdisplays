package com.dreamdisplays.platform.server.registrar

import io.github.arnodoelinger.platformweaver.PaperOnly

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.meta.Updater
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.utils.PlatformUtil
import java.util.concurrent.TimeUnit

/**
 * Manages the registration of scheduled tasks.
 */
@PaperOnly
object SchedulerRegistrar {
    /** The interval between display updates in ticks. */
    private const val TICKS_PER_SECOND = 20L

    /** Tick delay in milliseconds. */
    private const val TICK_MILLIS = 50L

    /** The interval between update checks in ticks. */
    private const val DISPLAY_UPDATE_INTERVAL_TICKS = 1L * TICKS_PER_SECOND

    /** The interval between update checks in ticks. */
    private const val UPDATE_CHECK_INTERVAL_TICKS = 60L * 60L * TICKS_PER_SECOND

    /** Schedules the periodic display update tick and, when enabled, the hourly update check. */
    fun runRepeatingTasks(plugin: PaperServer) {
        runRepeatingSync(
            plugin,
            DISPLAY_UPDATE_INTERVAL_TICKS,
            DISPLAY_UPDATE_INTERVAL_TICKS
        ) {
            if (PlatformUtil.isFolia) {
                DisplayManager.updateAllDisplaysForTrackedPlayers()
                StateManager.tickBroadcastForTrackedPlayers()
            } else {
                DisplayManager.updateAllDisplays()
                StateManager.tickBroadcast()
            }
            TimelineManager.tick()
            WatchPartyManager.tick()
        }
        val settings = PaperServer.config.settings
        if (settings.updatesEnabled) {
            runRepeatingAsync(
                plugin,
                TICKS_PER_SECOND,
                UPDATE_CHECK_INTERVAL_TICKS
            ) {
                Updater.checkForUpdates(
                    settings.repoOwner,
                    settings.repoName
                )
            }
        }
    }

    /** Schedules [task] on the `Paper` / `Folia` global tick scheduler. */
    private fun runRepeatingSync(plugin: PaperServer, delayTicks: Long, intervalTicks: Long, task: Runnable) {
        if (PlatformUtil.isFolia) {
            plugin.server.globalRegionScheduler.runAtFixedRate(
                plugin,
                { task.run() },
                delayTicks.coerceAtLeast(1L),
                intervalTicks.coerceAtLeast(1L),
            )
        } else {
            plugin.server.scheduler.runTaskTimer(plugin, task, delayTicks, intervalTicks)
        }
    }

    /** Schedules [task] on `Paper`'s async scheduler. */
    private fun runRepeatingAsync(plugin: PaperServer, delayTicks: Long, intervalTicks: Long, task: Runnable) {
        plugin.server.asyncScheduler.runAtFixedRate(
            plugin,
            { task.run() },
            delayTicks.coerceAtLeast(0L) * TICK_MILLIS,
            intervalTicks.coerceAtLeast(1L) * TICK_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }
}
