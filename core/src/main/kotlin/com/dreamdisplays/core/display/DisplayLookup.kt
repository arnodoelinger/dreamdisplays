package com.dreamdisplays.core.display

interface DisplayLookup {
    fun getDisplay(id: DisplayId): Display?
    fun listDisplays(): List<Display>
    fun onDisplayEvent(listener: (DisplayEvent) -> Unit): AutoCloseable
}
