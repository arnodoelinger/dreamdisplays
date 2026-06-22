package com.dreamdisplays.platform.server.registrar

import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.platform.server.Main
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.meta.Updater.checkForUpdates
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.scheduler.ProviderScheduler
import com.dreamdisplays.platform.server.utils.PlatformUtil

/**
 * Manages the registration of scheduled tasks.
 */
@PaperOnly object SchedulerRegistrar {
    /** The interval between display updates in ticks. */
    private const val TICKS_PER_SECOND = 20L

    /** The interval between update checks in ticks. */
    private const val DISPLAY_UPDATE_INTERVAL_TICKS = 1L * TICKS_PER_SECOND

    /** The interval between update checks in ticks. */
    private const val UPDATE_CHECK_INTERVAL_TICKS = 60L * 60L * TICKS_PER_SECOND

    /** Schedules the periodic display update tick and, when enabled, the hourly update check. */
    fun runRepeatingTasks(plugin: Main) {
        ProviderScheduler.adapter.runRepeatingSync(
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
        val settings = Main.config.settings
        if (settings.updatesEnabled) {
            ProviderScheduler.adapter.runRepeatingAsync(
                plugin,
                TICKS_PER_SECOND,
                UPDATE_CHECK_INTERVAL_TICKS
            ) {
                checkForUpdates(
                    settings.repoOwner,
                    settings.repoName
                )
            }
        }
    }
}
