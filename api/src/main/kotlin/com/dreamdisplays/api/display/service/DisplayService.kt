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

    /** Requests a server-authoritative video change for [id], optionally with the audio-track [lang]. */
    fun setUrl(id: DisplayId, url: String?, lang: String? = null)

    /** Locks or unlocks [id] (owner / admin); the server validates and echoes the new state. */
    fun setLocked(id: DisplayId, locked: Boolean)

    /** Deletes [id] entirely: purges its persisted data and unregisters it (owner / admin). */
    fun delete(id: DisplayId)

    /** Reports [id] for moderation review. */
    fun report(id: DisplayId)

    fun on(listener: (DisplayEvent) -> Unit): AutoCloseable
}
