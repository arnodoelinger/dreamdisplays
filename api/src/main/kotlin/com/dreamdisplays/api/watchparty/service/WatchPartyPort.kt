package com.dreamdisplays.api.watchparty.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.watchparty.model.WatchPartySession

/**
 * Watch party port.
 *
 * @since 1.8.x
 */
@Unstable
interface WatchPartyPort {
    /** Starts a watch party session for [displayId]. */
    fun start(displayId: DisplayId, url: String?): Boolean

    /** Sets the ready state for a watch party session. */
    fun setReady(displayId: DisplayId, ready: Boolean)

    /** Host: starts the synchronized countdown. */
    fun begin(displayId: DisplayId)

    /** Host: ends the session (freezes on the final frame). */
    fun end(displayId: DisplayId)

    /** Host / owner / admin: closes the session, returning the display to its base mode. */
    fun restartSession(displayId: DisplayId)

    /** Host / owner / admin: closes the session, returning the display to its base mode. */
    fun close(displayId: DisplayId)

    /** The live session on [displayId], or null when none is running. */
    fun getSession(displayId: DisplayId): WatchPartySession?
}
