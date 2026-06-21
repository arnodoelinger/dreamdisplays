package com.dreamdisplays.displays

import com.dreamdisplays.application.display.DisplayCommandExecutor
import com.dreamdisplays.core.display.Display
import com.dreamdisplays.core.display.DisplayId
import com.dreamdisplays.core.display.DisplaySettings
import com.dreamdisplays.core.playback.PlaybackMode
import kotlin.time.Duration

class MinecraftDisplayCommands : DisplayCommandExecutor {
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

    override fun setUrl(id: DisplayId, url: String?): Display? {
        val screen = DisplayRegistry.screens[id.uuid] ?: return null
        if (url.isNullOrBlank()) return screen.toDisplay()
        screen.playSuggestedVideo(url, screen.lang ?: "")
        return screen.toDisplay()
    }

    override fun play(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.setPaused(false)
        return screen.toDisplay()
    }

    override fun pause(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.setPaused(true)
        return screen.toDisplay()
    }

    override fun stop(displayId: DisplayId): Boolean {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return false
        DisplayRegistry.unregisterScreen(screen)
        return true
    }

    override fun seek(displayId: DisplayId, position: Duration): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.seekToMillis(position.inWholeMilliseconds)
        return screen.toDisplay()
    }

    override fun setVolume(displayId: DisplayId, volume: Float): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.volume = volume
        return screen.toDisplay()
    }

    override fun mute(displayId: DisplayId, muted: Boolean): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.mute(muted)
        return screen.toDisplay()
    }

    override fun restart(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        if (!screen.canSeekHere) return screen.toDisplay()
        val url = screen.videoUrl ?: return screen.toDisplay()
        screen.loadVideo(url, screen.lang ?: "")
        return screen.toDisplay()
    }

    override fun setMode(displayId: DisplayId, mode: PlaybackMode): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.requestMode(mode)
        return screen.toDisplay()
    }

    override fun startWatchParty(displayId: DisplayId, url: String?): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        if (!screen.canStartWatchPartyHere) return null
        screen.startWatchParty(url ?: screen.videoUrl ?: "")
        return screen.toDisplay()
    }

    override fun setWatchPartyReady(displayId: DisplayId, ready: Boolean): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.setWatchPartyReady(ready)
        return screen.toDisplay()
    }

    override fun beginWatchParty(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.beginWatchParty()
        return screen.toDisplay()
    }

    override fun endWatchParty(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.endWatchParty()
        return screen.toDisplay()
    }

    override fun restartWatchParty(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.restartWatchParty()
        return screen.toDisplay()
    }

    override fun closeWatchParty(displayId: DisplayId): Display? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.closeWatchParty()
        return screen.toDisplay()
    }
}
