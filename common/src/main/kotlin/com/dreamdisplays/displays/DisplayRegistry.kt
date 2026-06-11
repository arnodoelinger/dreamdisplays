package com.dreamdisplays.displays

import com.dreamdisplays.api.DisplayEvent
import com.dreamdisplays.api.DisplayId
import com.dreamdisplays.displays.store.ClientSettingsStore
import com.dreamdisplays.displays.store.FullDisplayData
import com.dreamdisplays.displays.store.ServerDisplayStore
import com.dreamdisplays.media.api.VideoQuality
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Central registry of all live [DisplayScreen]s and the event bus for their lifecycle changes. */
object DisplayRegistry {
    val screens = ConcurrentHashMap<UUID, DisplayScreen>()
    val unloadedScreens = ConcurrentHashMap<UUID, FullDisplayData>()

    private val eventListeners = CopyOnWriteArrayList<(DisplayEvent) -> Unit>()

    /** Returns a snapshot of all currently registered screens. */
    fun getScreens(): Collection<DisplayScreen> = screens.values

    /** Subscribes [listener] to display lifecycle events; returns an [AutoCloseable] to unsubscribe. */
    fun addListener(listener: (DisplayEvent) -> Unit): AutoCloseable {
        eventListeners.add(listener)
        return AutoCloseable { eventListeners.remove(listener) }
    }

    /** Dispatches [event] to all registered listeners. */
    fun emit(event: DisplayEvent) {
        eventListeners.forEach { it(event) }
    }

    /** Registers a new display screen. */
    fun registerScreen(displayScreen: DisplayScreen) {
        screens[displayScreen.uuid]?.unregister()

        val clientSettings = ClientSettingsStore.getSettings(displayScreen.uuid)
        displayScreen.volume = clientSettings.volume
        displayScreen.quality = VideoQuality.parse(clientSettings.quality)
        displayScreen.muted = clientSettings.muted

        ServerDisplayStore.getDisplayData(displayScreen.uuid)?.let { saved ->
            displayScreen.renderDistance = saved.renderDistance
            displayScreen.savedTimeNanos = saved.currentTimeNanos
        }

        screens[displayScreen.uuid] = displayScreen
        emit(DisplayEvent.Created(DisplayId(displayScreen.uuid), displayScreen.toDisplay()))
    }

    /** Unregisters a display screen, saving its data for later re-registration. */
    fun unregisterScreen(displayScreen: DisplayScreen) {
        unloadedScreens[displayScreen.uuid] = displayScreen.toFullDisplayData()
        screens.remove(displayScreen.uuid)
        displayScreen.unregister()
        emit(DisplayEvent.Removed(DisplayId(displayScreen.uuid)))
    }

    /** Unregisters all display screens. */
    fun unloadAll() {
        screens.values.forEach { it.unregister() }
        screens.clear()
        unloadedScreens.clear()
    }

    /** Saves the display screen data to disk. */
    fun saveScreenData(displayScreen: DisplayScreen) {
        ServerDisplayStore.saveDisplayData(displayScreen.uuid, displayScreen.toFullDisplayData())
    }

    /** Loads all display screens for a given server from disk. */
    fun loadScreensForServer(serverId: String) {
        ServerDisplayStore.load(serverId)
    }

    /** Saves all display screens to disk. */
    fun saveAllScreens() {
        screens.values.forEach { saveScreenData(it) }
    }
}
