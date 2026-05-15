package com.dreamdisplays.registrar

import com.dreamdisplays.Main
import com.dreamdisplays.managers.DisplayManager
import com.dreamdisplays.scheduler.ProviderScheduler
import com.dreamdisplays.meta.Updater.checkForUpdates

/**
 * Manages the registration of scheduled tasks.
 */
object SchedulerRegistrar {
    private const val TICKS_PER_SECOND = 20L
    private const val DISPLAY_UPDATE_INTERVAL_TICKS = 1L * TICKS_PER_SECOND
    private const val UPDATE_CHECK_INTERVAL_TICKS = 60L * 60L * TICKS_PER_SECOND

    fun runRepeatingTasks(plugin: Main) {
        // Update displays every second
        ProviderScheduler.adapter.runRepeatingAsync(
            plugin,
            DISPLAY_UPDATE_INTERVAL_TICKS,
            DISPLAY_UPDATE_INTERVAL_TICKS
        ) {
            DisplayManager.updateAllDisplays()
        }

        // Check for updates every hour
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
