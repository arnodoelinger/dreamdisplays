package com.dreamdisplays.managers

import com.dreamdisplays.Config

/**
 * Central client state shared by UI, playback, packet handlers, and render hooks.
 */
object ClientStateManager {
    val config: Config get() = ClientStartupManager.config
    val qualityRefreshThread: Thread get() = ClientStartupManager.qualityRefreshThread

    var isOnScreen: Boolean = false
    var focusMode: Boolean = false
    var displaysEnabled: Boolean = true
    var isPremium: Boolean = false
    var isAdmin: Boolean = false
    var isReportingEnabled: Boolean = true
}
