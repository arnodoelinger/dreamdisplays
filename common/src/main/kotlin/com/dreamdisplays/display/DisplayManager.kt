package com.dreamdisplays.display

import com.dreamdisplays.api.Display
import com.dreamdisplays.api.DisplayBounds
import com.dreamdisplays.api.DisplayEvent
import com.dreamdisplays.api.DisplayFacing
import com.dreamdisplays.api.DisplayId
import com.dreamdisplays.api.DisplayRuntimeState
import com.dreamdisplays.api.DisplaySettings as ApiDisplaySettings
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Data class representing a display screen in the world. Contains all necessary information for rendering and playback.
 *
 * @see DisplaySettings.FullDisplayData
 */
internal fun DisplayScreen.toDisplay(): Display = Display(
    id = DisplayId(uuid),
    bounds = DisplayBounds(
        x = pos.x.toDouble(), y = pos.y.toDouble(), z = pos.z.toDouble(),
        width = width, height = height,
        facing = facing,
    ),
    settings = ApiDisplaySettings(
        volume = volume, quality = quality, brightness = brightness,
        muted = muted, paused = isPaused, renderDistance = renderDistance,
        syncEnabled = isSync, urlOverride = null, audioTrackName = lang,
    ),
    url = videoUrl,
    state = toRuntimeState(),
)

internal fun DisplayScreen.toRuntimeState(): DisplayRuntimeState = when {
    mediaError != null -> DisplayRuntimeState.Failed(mediaError!!)
    videoUrl.isNullOrEmpty() -> DisplayRuntimeState.Idle
    !isVideoStarted -> DisplayRuntimeState.Preparing
    isPaused -> DisplayRuntimeState.Paused(uuid.toString(), currentTimeNanos / 1_000_000L)
    else -> DisplayRuntimeState.Playing(
        sessionId = uuid.toString(),
        positionMs = currentTimeNanos / 1_000_000L,
        durationMs = mediaPlayerDurationNanos.takeIf { it > 0L }?.let { it / 1_000_000L },
    )
}

private fun DisplayScreen.toFullDisplayData() = DisplaySettings.FullDisplayData(
    uuid, pos.x, pos.y, pos.z, facing, width, height,
    videoUrl ?: "", lang ?: "", volume, quality, brightness,
    muted, isSync, ownerUuid, renderDistance, currentTimeNanos,
)

/** Manager for all screen displays. */
object DisplayManager {
    val screens = ConcurrentHashMap<UUID, DisplayScreen>()
    val unloadedScreens = ConcurrentHashMap<UUID, DisplaySettings.FullDisplayData>()

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

        val clientSettings = DisplaySettings.getSettings(displayScreen.uuid)
        displayScreen.volume = clientSettings.volume
        displayScreen.quality = clientSettings.quality
        displayScreen.muted = clientSettings.muted

        DisplaySettings.getDisplayData(displayScreen.uuid)?.let { saved ->
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
        DisplaySettings.saveDisplayData(displayScreen.uuid, displayScreen.toFullDisplayData())
    }

    /** Loads all display screens for a given server from disk. */
    fun loadScreensForServer(serverId: String) {
        DisplaySettings.loadServerDisplays(serverId)
    }

    /** Saves all display screens to disk. */
    fun saveAllScreens() {
        screens.values.forEach { saveScreenData(it) }
    }
}
