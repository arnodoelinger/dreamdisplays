package com.dreamdisplays.core.playback

import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.playback.PlaybackMode.BROADCAST
import com.dreamdisplays.api.playback.PlaybackMode.LOCAL
import com.dreamdisplays.api.playback.PlaybackMode.SYNCED
import com.dreamdisplays.api.playback.PlaybackMode.WATCH_PARTY

/**
 * Inputs the permission rules need. Built the same way on the client (to grey out UI) and on the
 * server (to actually enforce). [mode] is the effective mode — `WATCH_PARTY` while a session runs,
 * otherwise the display's base mode. [isLocked] is the display's base lock flag (not the forced lock).
 */
data class PlaybackContext(
    val mode: PlaybackMode,
    val isOwner: Boolean,
    val isAdmin: Boolean,
    val isLocked: Boolean,
    val hasActiveParty: Boolean = false,
    val isPartyHost: Boolean = false,
)

/**
 * The single source of truth for who may do what in each [PlaybackMode]. The server is the real
 * enforcer; the client mirrors these rules purely for UX (enabling/hiding controls).
 */
object PlaybackPermissions {
    /** Max video height for [BROADCAST] displays; never exceeded, not even by the owner. */
    const val BROADCAST_QUALITY_CAP = 360

    /** Owner, admin, or anyone when the display is unlocked. */
    private fun isEditor(c: PlaybackContext): Boolean = c.isOwner || c.isAdmin || !c.isLocked

    /** Play / pause the timeline. Locked displays only allow owner / admin controls, even in Local. */
    fun canPlayPause(c: PlaybackContext): Boolean = when (c.mode) {
        LOCAL -> isEditor(c)
        SYNCED -> isEditor(c)
        WATCH_PARTY -> c.isPartyHost
        BROADCAST -> false
    }

    /** Seek the shared timeline (same authority as play / pause). */
    fun canSeek(c: PlaybackContext): Boolean = canPlayPause(c)

    /** Change the display's video URL. */
    fun canSetVideo(c: PlaybackContext): Boolean = when (c.mode) {
        WATCH_PARTY -> c.isPartyHost
        BROADCAST -> c.isOwner || c.isAdmin
        else -> isEditor(c)
    }

    /** Change the persistent base mode. Forbidden while a watch party is live. */
    fun canSetMode(c: PlaybackContext): Boolean =
        (c.isOwner || c.isAdmin || !c.isLocked) && !c.hasActiveParty

    /** Toggle the base lock. Impossible in Watch party / Broadcast (forced-locked there). */
    fun canToggleLock(c: PlaybackContext): Boolean =
        (c.isOwner || c.isAdmin) && c.mode != WATCH_PARTY && c.mode != BROADCAST

    /** Change the (personal) video quality. Broadcast is hard-capped and cannot be changed. */
    fun canChangeQuality(c: PlaybackContext): Boolean = c.mode != BROADCAST

    /** Open the Picture-in-Picture / windowed popout. Forbidden in Broadcast for everyone (owner / admin included). */
    fun canPopout(c: PlaybackContext): Boolean = c.mode != BROADCAST

    /** Start a watch party: anyone nearby when unlocked, owner/admin when locked. */
    fun canStartWatchParty(c: PlaybackContext): Boolean =
        !c.hasActiveParty && (c.isOwner || c.isAdmin || !c.isLocked)

    /** Drive an active session (begin / pause / seek / end / restart). Host only. */
    fun canControlWatchParty(c: PlaybackContext): Boolean = c.isPartyHost

    /** Close a session and free the display. Host, owner, or admin (covers a dead host). */
    fun canCloseWatchParty(c: PlaybackContext): Boolean = c.isPartyHost || c.isOwner || c.isAdmin

    /** The lock the world actually sees: the base lock, or forced on by Watch Party / Broadcast. */
    fun isEffectivelyLocked(mode: PlaybackMode, baseLocked: Boolean): Boolean =
        baseLocked || mode == WATCH_PARTY || mode == BROADCAST
}
