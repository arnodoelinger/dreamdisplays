package com.dreamdisplays.api.display.service

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.playback.PlaybackPort
import com.dreamdisplays.api.watchparty.WatchPartyPort

/**
 * Display system.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
interface DisplaySystem :
    DisplayLookup,
    DisplayMutationPort,
    PlaybackPort,
    WatchPartyPort {
    fun recordDisplay(display: Display)
    fun removeDisplay(id: DisplayId)
    fun clearDisplays()
    fun publish(event: DisplayEvent)
}
