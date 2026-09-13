package com.dreamdisplays.api.display.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.playback.service.PlaybackPort
import com.dreamdisplays.api.watchparty.service.WatchPartyPort

/**
 * Display system.
 *
 * @since 1.8.x
 */
@Unstable
interface DisplaySystem :
    DisplayLookup,
    DisplayMutationPort,
    PlaybackPort,
    WatchPartyPort {
    /** Records a new display in the "system". */
    fun recordDisplay(display: Display)

    /** Removes a display from the "system". */
    fun removeDisplay(id: DisplayId)

    /** Clear displays from the system. */
    fun clearDisplays()

    /** Publishes a display event to all listeners. */
    fun publish(event: DisplayEvent)
}
