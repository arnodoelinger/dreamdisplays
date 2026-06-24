package com.dreamdisplays.core.watchparty

import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.watchparty.WatchPartySession

interface WatchPartyPort {
    fun start(displayId: DisplayId, url: String?): Boolean
    fun setReady(displayId: DisplayId, ready: Boolean)
    fun begin(displayId: DisplayId)
    fun end(displayId: DisplayId)
    fun restartSession(displayId: DisplayId)
    fun close(displayId: DisplayId)
    fun getSession(displayId: DisplayId): WatchPartySession?
}
