package com.dreamdisplays.platform.server.datatypes.sync

import java.util.*

/**
 * Synchronization data for a display. Direction, current playback position, and state.
 */
data class SyncData(
    /** The unique identifier of the display. */
    val id: UUID?,

    /** Whether the display is synchronized. */
    val isSync: Boolean,

    /** Whether the display is currently paused. */
    val currentState: Boolean,

    /** The current playback position in nanoseconds. */
    val currentTime: Long,

    /** The duration limit of the display in nanoseconds. */
    val limitTime: Long,
)
