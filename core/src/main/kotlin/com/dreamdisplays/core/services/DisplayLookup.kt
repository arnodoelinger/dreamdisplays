package com.dreamdisplays.core.services

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.DisplayInfo
import com.dreamdisplays.api.display.model.property.DisplayId

/** Display lookup service. */
interface DisplayLookup {
    /** Get the display with the given [id], if it exists. */
    fun getDisplay(id: DisplayId): DisplayInfo?

    /** @return all displays currently visible to this service. */
    fun listDisplays(): List<DisplayInfo>

    /** Registers a listener for display events. */
    fun onDisplayEvent(listener: (DisplayEvent) -> Unit): AutoCloseable
}
