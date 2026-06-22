package com.dreamdisplays.api.display.service

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.display.model.DisplaySettings

/**
 * Public display registry and command surface. Implementations expose immutable [Display] snapshots
 * and forward mutations to the authoritative side, which validates ownership / permissions and
 * emits [DisplayEvent] updates.
 */
@DreamDisplaysUnstableApi interface DisplayService {
    /** Returns the latest known display snapshot for [id], or null when absent. */
    fun getDisplay(id: DisplayId): Display?

    /** Returns all displays currently visible to this service. */
    fun listDisplays(): List<Display>

    /** Replaces client-local or server-authoritative settings for [id], depending on implementation side. */
    fun updateSettings(id: DisplayId, settings: DisplaySettings)

    /** Requests a server-authoritative video change for [id], optionally with the audio-track [lang]. */
    fun setUrl(id: DisplayId, url: String?, lang: String? = null)

    /** Locks or unlocks [id] (owner / admin); the server validates and echoes the new state. */
    fun setLocked(id: DisplayId, locked: Boolean)

    /** Deletes [id] entirely: purges its persisted data and unregisters it (owner / admin). */
    fun delete(id: DisplayId)

    /** Reports [id] for moderation review. */
    fun report(id: DisplayId)

    /** Subscribes [listener] to display lifecycle events; close the returned handle to unsubscribe. */
    fun on(listener: (DisplayEvent) -> Unit): AutoCloseable
}
