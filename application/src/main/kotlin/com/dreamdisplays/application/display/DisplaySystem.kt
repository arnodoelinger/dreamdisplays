package com.dreamdisplays.application.display

import com.dreamdisplays.core.display.Display
import com.dreamdisplays.core.display.DisplayEvent
import com.dreamdisplays.core.display.DisplayId

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
