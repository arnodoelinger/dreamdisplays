package com.dreamdisplays.core.display.service

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.DisplayId

interface DisplayLookup {
    fun getDisplay(id: DisplayId): Display?
    fun listDisplays(): List<Display>
    fun onDisplayEvent(listener: (DisplayEvent) -> Unit): AutoCloseable
}
