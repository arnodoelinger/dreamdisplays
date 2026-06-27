package com.dreamdisplays.core.display.service.impl

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.display.model.DisplaySettings
import com.dreamdisplays.api.display.service.DisplayLookup
import com.dreamdisplays.api.display.service.DisplayMutationPort
import com.dreamdisplays.api.display.service.DisplayService

/**
 * Default core implementation of [DisplayService].
 */
class DefaultDisplayService(
    private val lookup: DisplayLookup,
    private val mutations: DisplayMutationPort,
) : DisplayService {
    /** Get the display with the given [id], or null if it doesn't exist. */
    override fun getDisplay(id: DisplayId): Display? = lookup.getDisplay(id)

    /** Returns all displays currently visible to this service. */
    override fun listDisplays(): List<Display> = lookup.listDisplays()

    /** Replaces client-local or server-authoritative settings for [id], depending on implementation side. */
    override fun updateSettings(id: DisplayId, settings: DisplaySettings) = mutations.updateSettings(id, settings)

    /** Requests a server-authoritative video change for [id], optionally with the audio-track [lang]. */
    override fun setUrl(id: DisplayId, url: String?, lang: String?) = mutations.setUrl(id, url, lang)

    /** Locks or unlocks [id] (owner / admin); the server validates and echoes the new state. */
    override fun setLocked(id: DisplayId, locked: Boolean) = mutations.setLocked(id, locked)

    /** Deletes [id] entirely: purges its persisted data and unregisters it (owner / admin). */
    override fun delete(id: DisplayId) = mutations.delete(id)

    /** Reports [id] for moderation review. */
    override fun report(id: DisplayId) = mutations.report(id)

    /** Subscribes [listener] to display lifecycle events; close the returned handle to unsubscribe. */
    override fun on(listener: (DisplayEvent) -> Unit): AutoCloseable = lookup.onDisplayEvent(listener)
}
