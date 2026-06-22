package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.display.DisplayCommandExecutor
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.display.model.DisplaySettings
import com.dreamdisplays.media.VideoQuality
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.core.protocol.DisplayDelete
import com.dreamdisplays.core.protocol.ReportDisplay
import com.dreamdisplays.core.protocol.SetLocked
import com.dreamdisplays.core.storage.DisplayStorage
import kotlin.time.Duration

/**
 * Client-side [DisplayCommandExecutor]: resolves each command to the live [DisplayScreen] in the
 * [DisplayRegistry] and applies it, returning the updated [Display] snapshot (or `null` when the
 * display is not present locally).
 */
class MinecraftDisplayCommands : DisplayCommandExecutor {
    /** Applies a full [settings] snapshot to the display, optionally swapping in a URL override. */
    override fun updateSettings(id: DisplayId, settings: DisplaySettings): Display? {
        val screen = DisplayRegistry.screens[id.uuid] ?: return null
        screen.volume = settings.volume
        screen.quality = settings.quality
        screen.brightness = settings.brightness
        screen.mute(settings.muted)
        screen.setPaused(settings.paused)
        screen.renderDistance = settings.renderDistance

        val override = settings.urlOverride
        if (!override.isNullOrBlank()) {
            screen.playSuggestedVideo(override, settings.audioTrackName ?: screen.lang ?: "")
        }

        return screen.toDisplay()
    }

    /** Plays [url] (with optional audio [lang]) on the display; no-op when [url] is blank. */
    override fun setUrl(id: DisplayId, url: String?, lang: String?): Display? {
        val screen = DisplayRegistry.screens[id.uuid] ?: return null
        if (url.isNullOrBlank()) return screen.toDisplay()
        screen.playSuggestedVideo(url, lang ?: screen.lang ?: "")
        return screen.toDisplay()
    }

    /** Locks or unlocks the display and informs the server. */
    override fun setLocked(id: DisplayId, locked: Boolean): Display? {
        val screen = DisplayRegistry.screens[id.uuid] ?: return null
        screen.isLocked = locked
        Initializer.sendPacket(SetLocked(id.uuid, locked))
        return screen.toDisplay()
    }

    /** Deletes the display locally, removes its persisted data, and notifies the server. */
    override fun delete(id: DisplayId): Boolean {
        val screen = DisplayRegistry.screens[id.uuid] ?: return false
        DisplayStorage.removeDisplay(id.uuid)
        DisplayRegistry.unregisterScreen(screen)
        Initializer.sendPacket(DisplayDelete(id.uuid))
        return true
    }

    /** Reports the display's current video to the server for moderation. */
    override fun report(id: DisplayId): Display? {
        val screen = DisplayRegistry.screens[id.uuid] ?: return null
        Initializer.sendPacket(ReportDisplay(id.uuid))
        return screen.toDisplay()
    }

    /** Resumes playback. */
    override fun play(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.setPaused(false)
        return screen.toDisplay()
    }

    /** Pauses playback. */
    override fun pause(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.setPaused(true)
        return screen.toDisplay()
    }

    /** Stops the display, unregistering it from the registry. */
    override fun stop(displayId: DisplayId): Boolean {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return false
        DisplayRegistry.unregisterScreen(screen)
        return true
    }

    /** Seeks to an absolute [position] in the current video. */
    override fun seek(displayId: DisplayId, position: Duration): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.seekToMillis(position.inWholeMilliseconds)
        return screen.toDisplay()
    }

    /** Seeks by a signed [delta] relative to the current position. */
    override fun seekRelative(displayId: DisplayId, delta: Duration): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.seekVideoRelative(delta.inWholeMilliseconds / 1000.0)
        return screen.toDisplay()
    }

    /** Sets the playback [volume] (`0.0`..`1.0`). */
    override fun setVolume(displayId: DisplayId, volume: Float): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.volume = volume
        return screen.toDisplay()
    }

    /** Sets the requested video [quality]. */
    override fun setQuality(displayId: DisplayId, quality: VideoQuality): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.quality = quality
        return screen.toDisplay()
    }

    /** Sets the display [brightness]. */
    override fun setBrightness(displayId: DisplayId, brightness: Float): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.brightness = brightness
        return screen.toDisplay()
    }

    /** Mutes or unmutes the display's audio. */
    override fun mute(displayId: DisplayId, muted: Boolean): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.mute(muted)
        return screen.toDisplay()
    }

    /** Reloads the current video from the start; no-op where seeking is not allowed. */
    override fun restart(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        if (!screen.canSeekHere) return screen.toDisplay()
        val url = screen.videoUrl ?: return screen.toDisplay()
        screen.loadVideo(url, screen.lang ?: "")
        return screen.toDisplay()
    }

    /** Requests a switch to the given playback [mode] (Local / Synced / Watch Party / Broadcast). */
    override fun setMode(displayId: DisplayId, mode: PlaybackMode): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.requestMode(mode)
        return screen.toDisplay()
    }

    /** Retries a failed video load. */
    override fun retry(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.retryVideo()
        return screen.toDisplay()
    }

    /** Starts a watch party on the display, defaulting to its current video when [url] is omitted. */
    override fun startWatchParty(displayId: DisplayId, url: String?): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        if (!screen.canStartWatchPartyHere) return null
        screen.startWatchParty(url ?: screen.videoUrl ?: "")
        return screen.toDisplay()
    }

    /** Marks the local participant ready / not-ready in the active watch party. */
    override fun setWatchPartyReady(displayId: DisplayId, ready: Boolean): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.setWatchPartyReady(ready)
        return screen.toDisplay()
    }

    /** Host action: begins the watch-party countdown. */
    override fun beginWatchParty(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.beginWatchParty()
        return screen.toDisplay()
    }

    /** Host action: ends the active watch party. */
    override fun endWatchParty(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.endWatchParty()
        return screen.toDisplay()
    }

    /** Host action: restarts an ended watch party. */
    override fun restartWatchParty(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.restartWatchParty()
        return screen.toDisplay()
    }

    /** Closes the watch party, returning the display to its base mode. */
    override fun closeWatchParty(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.closeWatchParty()
        return screen.toDisplay()
    }
}
