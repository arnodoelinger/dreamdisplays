package com.dreamdisplays.api

import com.dreamdisplays.display.DisplayManager
import com.dreamdisplays.display.toDisplay

/**
 * Default [DisplayService] backed by [DisplayManager].
 * Events are dispatched via the [DisplayManager] listener bus wired in [DisplayManager.addListener].
 *
 * @since 1.8.0
 */
class DefaultDisplayService : DisplayService {
    /** Creates a new display with the given settings and returns its ID. */
    override fun getDisplay(id: DisplayId): Display? =
        DisplayManager.screens[id.uuid]?.toDisplay()

    /** Returns all currently loaded displays. */
    override fun listDisplays(): List<Display> =
        DisplayManager.getScreens().map { it.toDisplay() }

    /** Updates the settings for [id]. */
    override fun updateSettings(id: DisplayId, settings: DisplaySettings) {
        val screen = DisplayManager.screens[id.uuid] ?: return
        screen.volume = settings.volume
        screen.quality = settings.quality
        screen.brightness = settings.brightness
        screen.mute(settings.muted)
        screen.setPaused(settings.paused)
        screen.renderDistance = settings.renderDistance
        screen.isSync = settings.syncEnabled
        val override = settings.urlOverride
        if (override != null) screen.loadVideo(override, settings.audioTrackName ?: "")
    }

    /** Sets the URL for [id]. */
    override fun setUrl(id: DisplayId, url: String?) {
        val screen = DisplayManager.screens[id.uuid] ?: return
        if (url.isNullOrBlank()) return
        screen.loadVideo(url, screen.lang ?: "")
    }

    /** Registers a listener for display events. */
    override fun on(listener: (DisplayEvent) -> Unit): AutoCloseable =
        DisplayManager.addListener(listener)
}
