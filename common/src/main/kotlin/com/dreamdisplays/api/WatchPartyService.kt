@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.api

/**
 * Controls ephemeral watch-party sessions on displays. Starting one is open to any nearby player
 * when the display is unlocked (owner / admin only when locked); once running, only the host drives
 * playback while everyone else can mark themselves ready. The server enforces all of this — these
 * calls are requests.
 *
 * @since 1.8.0
 */
interface WatchPartyService {
    /**
     * Requests a watch party on [displayId] with [url] (or the display's current video when null),
     * making the local player host. Returns false if the local client knows it isn't allowed.
     */
    fun start(displayId: DisplayId, url: String? = null): Boolean

    /** Marks the local player ready / not-ready during the ready-check. */
    fun setReady(displayId: DisplayId, ready: Boolean)

    /** Host: starts the synchronized countdown. */
    fun begin(displayId: DisplayId)

    /** Host: ends the session (freezes on the final frame). */
    fun end(displayId: DisplayId)

    /** Host: restarts an ended session from preparation. */
    fun restart(displayId: DisplayId)

    /** Host / owner / admin: closes the session, returning the display to its base mode. */
    fun close(displayId: DisplayId)

    /** The live session on [displayId], or null when none is running. */
    fun getSession(displayId: DisplayId): WatchPartySession?
}
