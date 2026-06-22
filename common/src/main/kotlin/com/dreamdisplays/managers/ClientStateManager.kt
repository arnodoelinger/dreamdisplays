package com.dreamdisplays.managers

import com.dreamdisplays.Config
import com.dreamdisplays.client.core.ClientMutableState

/**
 * Central client state shared by UI, playback, packet handlers, and render hooks.
 */
object ClientStateManager : ClientMutableState {
    val config: Config get() = ClientStartupManager.config

    override var isOnScreen: Boolean = false
    override var focusMode: Boolean = false
    override var displaysEnabled: Boolean = true
    override var isPremium: Boolean = false
    override var isAdmin: Boolean = false
    override var isReportingEnabled: Boolean = true
    override var connectedServerId: String? = null
}
