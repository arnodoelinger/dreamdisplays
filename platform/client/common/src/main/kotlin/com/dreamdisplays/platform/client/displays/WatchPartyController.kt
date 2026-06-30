package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.api.playback.WatchPartyAction
import com.dreamdisplays.core.protocol.WatchPartyControl
import com.dreamdisplays.core.protocol.WatchPartyStart

/**
 * Emits the local player's watch-party control intents for a [DisplayScreen]. Pure outbound command
 * emission: the server enforces participant / host rules and echoes authoritative state back through
 * [DisplayScreen.updateWatchParty]; nothing here mutates session state except the local ready toggle.
 *
 * Pulled out of [DisplayScreen] (like [DisplayMediaController] / [TimelineFollower]) so the screen no
 * longer interleaves command emission with render and sync state.
 */
internal class WatchPartyController(private val screen: DisplayScreen) {
    /** Starts a watch party here with the current (or given) video; the local player becomes host. */
    fun start(url: String, lang: String) {
        if (!screen.canStartWatchPartyHere) return
        if (url.isNotEmpty()) Initializer.sendPacket(WatchPartyStart(screen.uuid, url, lang))
    }

    /** Marks the local player ready / not-ready in the active watch party. */
    fun setReady(ready: Boolean) {
        if (screen.watchParty == null) return
        screen.localWatchPartyReady = ready
        send(if (ready) WatchPartyAction.READY else WatchPartyAction.UNREADY)
    }

    /** Host action: starts the countdown for the active watch party. */
    fun begin() {
        if (screen.watchParty?.isHost != true) return
        send(WatchPartyAction.BEGIN)
    }

    /** Host action: ends the active watch party (freezes on the final frame). */
    fun end() {
        if (screen.watchParty?.isHost != true) return
        send(WatchPartyAction.END)
    }

    /** Host action: restarts an ended watch party from preparation. */
    fun restart() {
        if (screen.watchParty?.isHost != true) return
        send(WatchPartyAction.RESTART)
    }

    /** Closes the watch party, handing the display back to its base mode (host / owner / admin). */
    fun close() {
        if (!screen.canCloseWatchPartyHere) return
        send(WatchPartyAction.CLOSE)
    }

    /** Sends a watch-party control for this display; the server enforces participant / host rules. */
    private fun send(action: WatchPartyAction, positionMs: Long = screen.currentTimeNanos / 1_000_000L) {
        Initializer.sendPacket(WatchPartyControl(screen.uuid, action.wire, positionMs))
    }
}
