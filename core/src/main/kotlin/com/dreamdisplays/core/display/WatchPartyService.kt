package com.dreamdisplays.core.display

/**
 * Controls ephemeral watch-party sessions on displays.
 */
interface WatchPartyService {
    fun start(displayId: DisplayId, url: String? = null): Boolean
    fun setReady(displayId: DisplayId, ready: Boolean)
    fun begin(displayId: DisplayId)
    fun end(displayId: DisplayId)
    fun restart(displayId: DisplayId)
    fun close(displayId: DisplayId)
    fun getSession(displayId: DisplayId): WatchPartySession?
}
