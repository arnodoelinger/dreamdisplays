package com.dreamdisplays.api.display.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.property.DisplayId

/**
 * Display lookup service.
 *
 * @since 1.8.x
 */
@Unstable
interface DisplayLookup {
    /** Get the display with the given [id], if it exists. */
    fun getDisplay(id: DisplayId): Display?

    /** Returns all displays currently visible to this service. */
    fun listDisplays(): List<Display>

    /** Registers a listener for display events. */
    fun onDisplayEvent(listener: (DisplayEvent) -> Unit): AutoCloseable
}
