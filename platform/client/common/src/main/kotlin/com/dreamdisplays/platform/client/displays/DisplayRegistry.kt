package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.display.service.DisplaySystem
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.api.runtime.getOrNull
import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import com.dreamdisplays.api.storage.FullDisplayData
import com.dreamdisplays.core.storage.DisplayStorage
import com.dreamdisplays.api.media.VideoQuality
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Central registry of all live [DisplayScreen]s and the event bus for their lifecycle changes. */
object DisplayRegistry {
    /** Screens registered by the application. */
    val screens = ConcurrentHashMap<UUID, DisplayScreen>()

    /** Screens unregistered by the application, but still in memory. */
    val unloadedScreens = ConcurrentHashMap<UUID, FullDisplayData>()

    /** Event listeners subscribed to display lifecycle events. */
    private val eventListeners = CopyOnWriteArrayList<(DisplayEvent) -> Unit>()

    /** The display system, if available. */
    private val displaySystem: DisplaySystem? get() = DreamServices.registry.getOrNull<DisplaySystem>()

    /** Returns a snapshot of all currently registered screens. */
    fun getScreens(): Collection<DisplayScreen> = screens.values

    /** Number of screens currently parked warm (dormant), used to bound the warm-park pool. */
    fun dormantCount(): Int = screens.values.count { it.isDormant }

    /** Snapshot of screens currently parked fully warm, used by the adaptive warm-park budget. */
    fun dormantScreens(): List<DisplayScreen> = screens.values.filter { it.isDormant }

    /** Subscribes [listener] to display lifecycle events; returns an [AutoCloseable] to unsubscribe. */
    fun addListener(listener: (DisplayEvent) -> Unit): AutoCloseable {
        displaySystem?.let { return it.onDisplayEvent(listener) }
        eventListeners.add(listener)
        return AutoCloseable { eventListeners.remove(listener) }
    }

    /** Dispatches [event] to all registered listeners. */
    fun emit(event: DisplayEvent) {
        displaySystem?.publish(event) ?: eventListeners.forEach { it(event) }
    }

    /** Publishes the current screen snapshot into the application display system. */
    fun recordScreen(displayScreen: DisplayScreen) {
        if (!screens.containsKey(displayScreen.uuid)) return
        displaySystem?.recordDisplay(displayScreen.toDisplay())
    }

    /** Registers a new display screen. */
    fun registerScreen(displayScreen: DisplayScreen) {
        screens[displayScreen.uuid]?.unregister()

        val clientSettings = ClientSettingsStore.getSettings(
            displayScreen.uuid,
            DisplayScreen.defaultVolumeFor(displayScreen.mode),
        )
        displayScreen.volume = clientSettings.volume
        displayScreen.quality = VideoQuality.parse(clientSettings.quality)
        displayScreen.muted = clientSettings.muted

        DisplayStorage.getDisplayData(displayScreen.uuid)?.let { saved ->
            displayScreen.renderDistance = saved.renderDistance
            displayScreen.savedTimeNanos = saved.currentTimeNanos
        }

        screens[displayScreen.uuid] = displayScreen
        recordScreen(displayScreen)
    }

    /** Unregisters a display screen, saving its data for later re-registration. */
    fun unregisterScreen(displayScreen: DisplayScreen) {
        unloadedScreens[displayScreen.uuid] = displayScreen.toFullDisplayData()
        screens.remove(displayScreen.uuid)
        displayScreen.unregister()
        displaySystem?.removeDisplay(DisplayId(displayScreen.uuid))
    }

    /** Unregisters all display screens. */
    fun unloadAll() {
        screens.values.forEach { it.unregister() }
        screens.clear()
        unloadedScreens.clear()
        displaySystem?.clearDisplays()
    }

    /** Saves the display screen data to disk. */
    fun saveScreenData(displayScreen: DisplayScreen) {
        DisplayStorage.saveDisplayData(displayScreen.uuid, displayScreen.toFullDisplayData())
    }

    /** Loads all display screens for a given server from disk. */
    fun loadScreensForServer(serverId: String) {
        DisplayStorage.load(serverId)
    }

    /** Saves all display screens to disk. */
    fun saveAllScreens() {
        screens.values.forEach { saveScreenData(it) }
    }
}
