package com.dreamdisplays.application.display

import com.dreamdisplays.api.DisplayService
import com.dreamdisplays.core.display.Display
import com.dreamdisplays.core.display.DisplayEvent
import com.dreamdisplays.core.display.DisplayId
import com.dreamdisplays.core.display.DisplaySettings

/**
 * Default application implementation of [DisplayService].
 */
class DefaultDisplayService(
    private val lookup: DisplayLookup,
    private val mutations: DisplayMutationPort,
) : DisplayService {
    override fun getDisplay(id: DisplayId): Display? = lookup.getDisplay(id)

    override fun listDisplays(): List<Display> = lookup.listDisplays()

    override fun updateSettings(id: DisplayId, settings: DisplaySettings) =
        mutations.updateSettings(id, settings)

    override fun setUrl(id: DisplayId, url: String?) =
        mutations.setUrl(id, url)

    override fun on(listener: (DisplayEvent) -> Unit): AutoCloseable =
        lookup.onDisplayEvent(listener)
}
