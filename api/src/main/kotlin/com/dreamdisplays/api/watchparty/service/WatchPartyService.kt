package com.dreamdisplays.api.watchparty.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.watchparty.model.WatchPartySession

/**
 * Controls ephemeral watch-party sessions on displays; open to nearby players when display is unlocked.
 *
 * @since 1.8.x
 */
@Unstable
interface WatchPartyService {
    /**
     * Requests a watch party on [displayId] with [url] (or the display's current video when null),
     * making the local player host. Returns false if the local client knows it isn't allowed.
     */
    fun start(displayId: DisplayId, url: String? = null): Boolean

    /** Marks the local player ready / not-ready during the ready-check. */
    fun setReady(displayId: DisplayId, ready: Boolean)

    /** Host: starts the synchronized countdown. */
    fun begin(displayId: DisplayId)

    /** Host: ends the session (freezes on the final frame). */
    fun end(displayId: DisplayId)

    /** Host: restarts an ended session from preparation. */
    fun restart(displayId: DisplayId)

    /** Host / owner / admin: closes the session, returning the display to its base mode. */
    fun close(displayId: DisplayId)

    /** The live session on [displayId], or null when none is running. */
    fun getSession(displayId: DisplayId): WatchPartySession?
}
