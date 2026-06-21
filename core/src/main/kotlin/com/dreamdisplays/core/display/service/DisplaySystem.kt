package com.dreamdisplays.core.display.service

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.core.playback.PlaybackPort
import com.dreamdisplays.core.watchparty.WatchPartyPort

interface DisplaySystem :
    DisplayLookup,
    DisplayMutationPort,
    PlaybackPort,
    WatchPartyPort {
    fun recordDisplay(display: Display)
    fun removeDisplay(id: DisplayId)
    fun clearDisplays()
    fun publish(event: DisplayEvent)
}
