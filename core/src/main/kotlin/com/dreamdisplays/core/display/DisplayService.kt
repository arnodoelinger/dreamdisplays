package com.dreamdisplays.core.display

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
