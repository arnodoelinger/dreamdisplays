package com.dreamdisplays.application.display

import com.dreamdisplays.core.display.Display
import com.dreamdisplays.core.display.DisplayEvent
import com.dreamdisplays.core.display.DisplayId

interface DisplayLookup {
    fun getDisplay(id: DisplayId): Display?
    fun listDisplays(): List<Display>
    fun onDisplayEvent(listener: (DisplayEvent) -> Unit): AutoCloseable
}
