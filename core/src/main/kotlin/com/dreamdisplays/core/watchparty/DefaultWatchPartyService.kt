package com.dreamdisplays.core.watchparty

import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.watchparty.WatchPartyPort
import com.dreamdisplays.api.watchparty.WatchPartyService
import com.dreamdisplays.api.watchparty.WatchPartySession

/**
 * Default core implementation of [WatchPartyService].
 */
class DefaultWatchPartyService(
    private val watchParty: WatchPartyPort,
) : WatchPartyService {
    /** Starts a watch party session for the display with the given [id], or null if it doesn't exist. */
    override fun start(displayId: DisplayId, url: String?): Boolean = watchParty.start(displayId, url)

    /** Sets the ready state for a watch party session. */
    override fun setReady(displayId: DisplayId, ready: Boolean) = watchParty.setReady(displayId, ready)

    /** Host: starts the synchronized countdown. */
    override fun begin(displayId: DisplayId) = watchParty.begin(displayId)

    /** Host: ends the session (freezes on the final frame). */
    override fun end(displayId: DisplayId) = watchParty.end(displayId)

    /** Host / owner / admin: closes the session, returning the display to its base mode. */
    override fun restart(displayId: DisplayId) = watchParty.restartSession(displayId)

    /** Host / owner / admin: closes the session, returning the display to its base mode. */
    override fun close(displayId: DisplayId) = watchParty.close(displayId)

    /** The live session on [displayId], or null when none is running. */
    override fun getSession(displayId: DisplayId): WatchPartySession? = watchParty.getSession(displayId)
}
