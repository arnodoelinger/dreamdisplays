package com.dreamdisplays.core.display.service.impl

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.display.model.DisplayRuntimeState
import com.dreamdisplays.api.display.model.DisplaySettings
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.watchparty.WatchPartySession
import com.dreamdisplays.core.display.DisplayCommandExecutor
import com.dreamdisplays.core.display.service.DisplaySystem
import com.dreamdisplays.media.VideoQuality
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

class DefaultDisplaySystem(
    private val commands: DisplayCommandExecutor = DisplayCommandExecutor.Noop,
) : DisplaySystem {
    private val displays = ConcurrentHashMap<DisplayId, Display>()
    private val listeners = CopyOnWriteArrayList<(DisplayEvent) -> Unit>()

    override fun getDisplay(id: DisplayId): Display? = displays[id]

    override fun listDisplays(): List<Display> =
        displays.values.sortedBy { it.id.uuid.toString() }

    override fun onDisplayEvent(listener: (DisplayEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun recordDisplay(display: Display) {
        val previous = displays.put(display.id, display)
        if (previous == null) {
            publish(DisplayEvent.Created(display.id, display))
            return
        }

        publishChanges(previous, display)
    }

    override fun removeDisplay(id: DisplayId) {
        if (displays.remove(id) != null) {
            publish(DisplayEvent.Removed(id))
        }
    }

    override fun clearDisplays() {
        displays.keys.toList().forEach(::removeDisplay)
    }

    override fun publish(event: DisplayEvent) {
        listeners.forEach { it(event) }
    }

    override fun updateSettings(id: DisplayId, settings: DisplaySettings) {
        apply(commands.updateSettings(id, settings))
    }

    override fun setUrl(id: DisplayId, url: String?, lang: String?) {
        apply(commands.setUrl(id, url, lang))
    }

    override fun setLocked(id: DisplayId, locked: Boolean) {
        apply(commands.setLocked(id, locked))
    }

    override fun delete(id: DisplayId) {
        if (commands.delete(id)) removeDisplay(id)
    }

    override fun report(id: DisplayId) {
        apply(commands.report(id))
    }

    override fun play(displayId: DisplayId) {
        apply(commands.play(displayId))
    }

    override fun pause(displayId: DisplayId) {
        apply(commands.pause(displayId))
    }

    override fun stop(displayId: DisplayId) {
        if (commands.stop(displayId)) removeDisplay(displayId)
    }

    override fun seek(displayId: DisplayId, position: Duration) {
        apply(commands.seek(displayId, position))
    }

    override fun seekRelative(displayId: DisplayId, delta: Duration) {
        apply(commands.seekRelative(displayId, delta))
    }

    override fun setVolume(displayId: DisplayId, volume: Float) {
        apply(commands.setVolume(displayId, volume))
    }

    override fun setQuality(displayId: DisplayId, quality: VideoQuality) {
        apply(commands.setQuality(displayId, quality))
    }

    override fun setBrightness(displayId: DisplayId, brightness: Float) {
        apply(commands.setBrightness(displayId, brightness))
    }

    override fun mute(displayId: DisplayId, muted: Boolean) {
        apply(commands.mute(displayId, muted))
    }

    override fun getState(displayId: DisplayId): DisplayRuntimeState =
        displays[displayId]?.state ?: DisplayRuntimeState.OutOfRange

    override fun restart(displayId: DisplayId) {
        apply(commands.restart(displayId))
    }

    override fun getMode(displayId: DisplayId): PlaybackMode =
        displays[displayId]?.mode ?: PlaybackMode.LOCAL

    override fun setMode(displayId: DisplayId, mode: PlaybackMode) {
        apply(commands.setMode(displayId, mode))
    }

    override fun retry(displayId: DisplayId) {
        apply(commands.retry(displayId))
    }

    override fun start(displayId: DisplayId, url: String?): Boolean {
        val display = commands.startWatchParty(displayId, url) ?: return false
        recordDisplay(display)
        return true
    }

    override fun setReady(displayId: DisplayId, ready: Boolean) {
        apply(commands.setWatchPartyReady(displayId, ready))
    }

    override fun begin(displayId: DisplayId) {
        apply(commands.beginWatchParty(displayId))
    }

    override fun end(displayId: DisplayId) {
        apply(commands.endWatchParty(displayId))
    }

    override fun restartSession(displayId: DisplayId) {
        apply(commands.restartWatchParty(displayId))
    }

    override fun close(displayId: DisplayId) {
        apply(commands.closeWatchParty(displayId))
    }

    override fun getSession(displayId: DisplayId): WatchPartySession? =
        displays[displayId]?.watchParty

    private fun apply(display: Display?) {
        if (display != null) recordDisplay(display)
    }

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
