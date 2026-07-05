package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.playback.PlaybackContext
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.playback.PlaybackPermissions
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import java.util.UUID

/**
 * Builds the [PlaybackContext] the shared [PlaybackPermissions] rules
 * consume, folding in any live watch-party session so the effective mode and host identity are
 * correct. Used by every server entry point that enforces permissions.
 */
object PlaybackContexts {
    /** `WATCH_PARTY` while a session is live on [display], otherwise the persistent base mode. */
    fun effectiveMode(display: DisplayData): PlaybackMode =
        if (WatchPartyManager.hasSession(display.id)) PlaybackMode.WATCH_PARTY else display.mode

    /** The permission context for [senderId] acting on [display]; [isAdmin] comes from the platform. */
    fun of(display: DisplayData, senderId: UUID, isAdmin: Boolean): PlaybackContext = PlaybackContext(
        mode = effectiveMode(display),
        isOwner = display.ownerId == senderId,
        isAdmin = isAdmin,
        isLocked = display.isLocked,
        hasActiveParty = WatchPartyManager.hasSession(display.id),
        isPartyHost = WatchPartyManager.isHost(display.id, senderId),
    )
}
