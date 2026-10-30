package com.dreamdisplays.core.services

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.DisplayInfo
import com.dreamdisplays.api.display.model.property.DisplayId

/**
 * Display system.
 */
interface DisplaySystem :
    DisplayLookup,
    DisplayMutationPort,
    PlaybackPort,
    WatchPartyPort {
    /** Records a new display in the system. */
    fun recordDisplay(display: DisplayInfo)

    /** Removes a display from the system. */
    fun removeDisplay(id: DisplayId)

    /** Clear displays from the system. */
    fun clearDisplays()

    /** Publishes a display event to all listeners. */
    fun publish(event: DisplayEvent)
}
