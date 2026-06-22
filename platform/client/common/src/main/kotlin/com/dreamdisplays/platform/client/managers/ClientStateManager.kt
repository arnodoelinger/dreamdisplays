package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.platform.client.Config
import com.dreamdisplays.platform.client.core.ClientMutableState

/**
 * Central client state shared by UI, playback, packet handlers, and render hooks.
 */
object ClientStateManager : ClientMutableState {
    /** The active client [Config]. */
    val config: Config get() = ClientStartupManager.config

    /** Whether a display menu / screen is currently open. */
    override var isOnScreen: Boolean = false

    /** Whether focus mode is active. */
    override var focusMode: Boolean = false

    /** Whether displays are enabled. */
    override var displaysEnabled: Boolean = true

    /** Whether the local player is a premium user. */
    override var isPremium: Boolean = false

    /** Whether the local player has admin (OP) permissions. */
    override var isAdmin: Boolean = false

    /** Whether reporting videos to the server is enabled. */
    override var isReportingEnabled: Boolean = true

    /** Id of the connected server, or `null` when not connected. */
    override var connectedServerId: String? = null
}
