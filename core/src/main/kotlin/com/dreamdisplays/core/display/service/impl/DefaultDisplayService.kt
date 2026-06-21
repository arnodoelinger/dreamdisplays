package com.dreamdisplays.core.display.service.impl

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.display.model.DisplaySettings
import com.dreamdisplays.api.display.service.DisplayService
import com.dreamdisplays.core.display.service.DisplayLookup
import com.dreamdisplays.core.display.service.DisplayMutationPort

/**
 * Default core implementation of [DisplayService].
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
