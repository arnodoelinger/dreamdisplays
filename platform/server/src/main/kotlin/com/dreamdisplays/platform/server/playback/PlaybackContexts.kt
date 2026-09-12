package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackContext
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.policy.PlaybackPermissions
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import java.util.*

/**
 * Builds the [PlaybackContext] the shared [PlaybackPermissions] rules
 * consume, folding in any live watch-party session so the effective mode and host identity are
 * correct. Used by every server entry point that enforces permissions.
 */
object PlaybackContexts {
    /** `WATCH_PARTY` while a session is live on [display], otherwise the persistent base mode. */
    fun effectiveMode(display: DisplayData): PlaybackMode =
        if (WatchPartyManager.hasSession(display.id)) PlaybackMode.WATCH_PARTY else display.mode

    /**
     * The permission context for [senderId] acting on [display]; [isAdmin] comes from the platform.
     */
    fun of(
        display: DisplayData, senderId: UUID, isAdmin: Boolean,
        territoryMember: () -> Boolean = { false },
    ): PlaybackContext {
        val mode = effectiveMode(display)
        val forcedLock = mode == PlaybackMode.WATCH_PARTY || mode == PlaybackMode.BROADCAST
        val locked = when {
            forcedLock -> true
            display.access == DisplayAccess.EVERYONE -> false
            display.access == DisplayAccess.REGION -> !territoryMember()
            else -> true
        }
        return PlaybackContext(
            mode = mode,
            isOwner = display.ownerId == senderId,
            isAdmin = isAdmin,
            isLocked = locked,
            hasActiveParty = WatchPartyManager.hasSession(display.id),
            isPartyHost = WatchPartyManager.isHost(display.id, senderId),
        )
    }
}
