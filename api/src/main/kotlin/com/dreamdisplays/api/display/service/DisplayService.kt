package com.dreamdisplays.api.display.service

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.display.model.DisplaySettings

/**
 * Represents a display in the system.
 */
interface DisplayService {
    fun getDisplay(id: DisplayId): Display?
    fun listDisplays(): List<Display>
    fun updateSettings(id: DisplayId, settings: DisplaySettings)
    fun setUrl(id: DisplayId, url: String?)
    fun on(listener: (DisplayEvent) -> Unit): AutoCloseable
}
