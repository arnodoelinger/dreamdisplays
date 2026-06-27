package com.dreamdisplays.api.display.service

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.DisplayId

/**
 * Display lookup service.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
interface DisplayLookup {
    /** Get the display with the given [id], if it exists. */
    fun getDisplay(id: DisplayId): Display?

    /** Returns all displays currently visible to this service. */
    fun listDisplays(): List<Display>

    /** Registers a listener for display events. */
    fun onDisplayEvent(listener: (DisplayEvent) -> Unit): AutoCloseable
}
