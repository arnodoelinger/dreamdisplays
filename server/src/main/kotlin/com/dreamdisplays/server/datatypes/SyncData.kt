package com.dreamdisplays.server.datatypes

import java.util.*

/**
 * Synchronization data for a display. Direction, current playback position, and state.
 *
 * @param id unique identifier of the display. `null` for broadcast packets targeting all displays.
 * @param isSync whether this packet carries an authoritative sync state.
 * @param currentState `true` = paused, `false` = playing.
 * @param currentTime current playback position in ns.
 * @param limitTime duration of the display in ns.
 */
data class SyncData(
    val id: UUID?,
    val isSync: Boolean,
    val currentState: Boolean,
    val currentTime: Long,
    val limitTime: Long,
)
