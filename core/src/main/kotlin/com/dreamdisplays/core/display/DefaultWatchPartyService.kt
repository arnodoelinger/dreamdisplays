package com.dreamdisplays.core.display

import com.dreamdisplays.core.display.WatchPartyService
import com.dreamdisplays.core.display.WatchPartySession

/**
 * Default core implementation of [WatchPartyService].
 */
class DefaultWatchPartyService(
    private val watchParty: WatchPartyPort,
) : WatchPartyService {
    override fun start(displayId: DisplayId, url: String?): Boolean = watchParty.start(displayId, url)

    override fun setReady(displayId: DisplayId, ready: Boolean) = watchParty.setReady(displayId, ready)

    override fun begin(displayId: DisplayId) = watchParty.begin(displayId)

    override fun end(displayId: DisplayId) = watchParty.end(displayId)

    override fun restart(displayId: DisplayId) = watchParty.restartSession(displayId)

    override fun close(displayId: DisplayId) = watchParty.close(displayId)

    override fun getSession(displayId: DisplayId): WatchPartySession? = watchParty.getSession(displayId)
}
