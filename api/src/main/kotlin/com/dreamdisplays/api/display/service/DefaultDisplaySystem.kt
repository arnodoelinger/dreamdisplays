package com.dreamdisplays.api.display.service

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.display.model.DisplayRuntimeState
import com.dreamdisplays.api.display.model.DisplaySettings
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.watchparty.WatchPartySession
import com.dreamdisplays.api.media.VideoQuality
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * Default implementation of [DisplaySystem].
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
class DefaultDisplaySystem(
    private val commands: DisplayCommandExecutor = DisplayCommandExecutor.Noop,
) : DisplaySystem {
    /** Displays currently managed by this system. */
    private val displays = ConcurrentHashMap<DisplayId, Display>()

    /** Listeners for display events. */
    private val listeners = CopyOnWriteArrayList<(DisplayEvent) -> Unit>()

    /** Gets the display with the given [id], or null if it doesn't exist. */
    override fun getDisplay(id: DisplayId): Display? = displays[id]

    /** Returns all displays currently visible to this system. */
    override fun listDisplays(): List<Display> = displays.values.sortedBy { it.id.uuid.toString() }

    /** Registers a listener for display events. */
    override fun onDisplayEvent(listener: (DisplayEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Records a display in the system. */
    override fun recordDisplay(display: Display) {
        val previous = displays.put(display.id, display)
        if (previous == null) {
            publish(DisplayEvent.Created(display.id, display))
            return
        }

        publishChanges(previous, display)
    }

    /** Removes a display from the system. */
    override fun removeDisplay(id: DisplayId) {
        if (displays.remove(id) != null) {
            publish(DisplayEvent.Removed(id))
        }
    }

    /** Removes all displays from the system. */
    override fun clearDisplays() {
        displays.keys.toList().forEach(::removeDisplay)
    }

    /** Publishes a display event to all listeners. */
    override fun publish(event: DisplayEvent) {
        listeners.forEach { it(event) }
    }

    /** Update display settings. */
    override fun updateSettings(id: DisplayId, settings: DisplaySettings) {
        apply(commands.updateSettings(id, settings))
    }

    /** Update display URL. */
    override fun setUrl(id: DisplayId, url: String?, lang: String?) {
        apply(commands.setUrl(id, url, lang))
    }

    /** Lock or unlock a display. */
    override fun setLocked(id: DisplayId, locked: Boolean) {
        apply(commands.setLocked(id, locked))
    }

    /** Delete a display. */
    override fun delete(id: DisplayId) {
        if (commands.delete(id)) removeDisplay(id)
    }

    /** Report a display for moderation review. */
    override fun report(id: DisplayId) {
        apply(commands.report(id))
    }

    /** Play a display. */
    override fun play(displayId: DisplayId) {
        apply(commands.play(displayId))
    }

    /** Pause a display. */
    override fun pause(displayId: DisplayId) {
        apply(commands.pause(displayId))
    }

    /** Stop a display. */
    override fun stop(displayId: DisplayId) {
        if (commands.stop(displayId)) removeDisplay(displayId)
    }

    /** Seek to a specific position in a display. */
    override fun seek(displayId: DisplayId, position: Duration) {
        apply(commands.seek(displayId, position))
    }

    /** Seek [delta] relative to the current position for [displayId] (negative = backward). */
    override fun seekRelative(displayId: DisplayId, delta: Duration) {
        apply(commands.seekRelative(displayId, delta))
    }

    /** Set the volume for a display. */
    override fun setVolume(displayId: DisplayId, volume: Float) {
        apply(commands.setVolume(displayId, volume))
    }

    /** Set the preferred video quality for a display. */
    override fun setQuality(displayId: DisplayId, quality: VideoQuality) {
        apply(commands.setQuality(displayId, quality))
    }

    /** Set the active audio track for a display. */
    override fun setAudioTrack(displayId: DisplayId, trackUrl: String) {
        apply(commands.setAudioTrack(displayId, trackUrl))
    }

    /** Set the brightness multiplier for a display. */
    override fun setBrightness(displayId: DisplayId, brightness: Float) {
        apply(commands.setBrightness(displayId, brightness))
    }

    /** Mute or unmute the audio for a display. */
    override fun mute(displayId: DisplayId, muted: Boolean) {
        apply(commands.mute(displayId, muted))
    }

    /** Gets the runtime state for a display. */
    override fun getState(displayId: DisplayId): DisplayRuntimeState =
        displays[displayId]?.state ?: DisplayRuntimeState.OutOfRange

    /** Restarts the video for a display. */
    override fun restart(displayId: DisplayId) {
        apply(commands.restart(displayId))
    }

    /** The effective [PlaybackMode] of a display (`WATCH_PARTY` while a session is live). */
    override fun getMode(displayId: DisplayId): PlaybackMode =
        displays[displayId]?.mode ?: PlaybackMode.LOCAL

    /** Requests a new persistent base mode (`LOCAL` / `SYNCED` / `BROADCAST`); the server validates it. */
    override fun setMode(displayId: DisplayId, mode: PlaybackMode) {
        apply(commands.setMode(displayId, mode))
    }

    /** Re-resolves and reloads the current video for [displayId] after a load failure (local recovery). */
    override fun retry(displayId: DisplayId) {
        apply(commands.retry(displayId))
    }

    /** Starts a watch party session for a display. */
    override fun start(displayId: DisplayId, url: String?): Boolean {
        val display = commands.startWatchParty(displayId, url) ?: return false
        recordDisplay(display)
        return true
    }

    /** Sets the ready state for a watch party session. */
    override fun setReady(displayId: DisplayId, ready: Boolean) {
        apply(commands.setWatchPartyReady(displayId, ready))
    }

    /** Starts a watch party session for a display. */
    override fun begin(displayId: DisplayId) {
        apply(commands.beginWatchParty(displayId))
    }

    /** Ends a watch party session for a display. */
    override fun end(displayId: DisplayId) {
        apply(commands.endWatchParty(displayId))
    }

    /** Restarts a watch party session for a display. */
    override fun restartSession(displayId: DisplayId) {
        apply(commands.restartWatchParty(displayId))
    }

    /** Closes a watch party session for a display. */
    override fun close(displayId: DisplayId) {
        apply(commands.closeWatchParty(displayId))
    }

    /** Gets the live session for a display, or null if none is running. */
    override fun getSession(displayId: DisplayId): WatchPartySession? =
        displays[displayId]?.watchParty

    /** Applies a display update to the system, if it's valid. */
    private fun apply(display: Display?) {
        if (display != null) recordDisplay(display)
    }

    /** Publishes changes to a display. */
    private fun publishChanges(previous: Display, current: Display) {
        if (previous.settings != current.settings) {
            publish(DisplayEvent.SettingsChanged(current.id, previous.settings, current.settings))
        }
        if (previous.state != current.state) {
            publish(DisplayEvent.StateChanged(current.id, previous.state, current.state))
        }
        if (previous.url != current.url) {
            publish(DisplayEvent.UrlChanged(current.id, current.url))
        }
    }
}
