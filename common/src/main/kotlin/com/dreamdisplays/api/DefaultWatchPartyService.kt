@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.api

import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.displays.DisplayScreen

/**
 * Default [WatchPartyService] backed by [DisplayRegistry]; delegates to the per-display intent
 * helpers on [DisplayScreen], which send the v2 watch-party packets.
 *
 * @since 1.8.0
 */
class DefaultWatchPartyService : WatchPartyService {
    private fun screen(id: DisplayId): DisplayScreen? = DisplayRegistry.screens[id.uuid]

    /** Starts a party with [url] (or the display's current video), if the client believes it's allowed. */
    override fun start(displayId: DisplayId, url: String?): Boolean {
        val screen = screen(displayId) ?: return false
        if (!screen.canStartWatchPartyHere) return false
        screen.startWatchParty(url ?: screen.videoUrl ?: "")
        return true
    }

    /** Marks the local player ready / not-ready. */
    override fun setReady(displayId: DisplayId, ready: Boolean) {
        screen(displayId)?.setWatchPartyReady(ready)
    }

    /** Host action: starts the countdown. */
    override fun begin(displayId: DisplayId) {
        screen(displayId)?.beginWatchParty()
    }

    /** Host action: ends the session. */
    override fun end(displayId: DisplayId) {
        screen(displayId)?.endWatchParty()
    }

    /** Host action: restarts an ended session. */
    override fun restart(displayId: DisplayId) {
        screen(displayId)?.restartWatchParty()
    }

    /** Closes the session and returns the display to its base mode. */
    override fun close(displayId: DisplayId) {
        screen(displayId)?.closeWatchParty()
    }

    /** The live session for [displayId], or null. */
    override fun getSession(displayId: DisplayId): WatchPartySession? = screen(displayId)?.watchParty
}
