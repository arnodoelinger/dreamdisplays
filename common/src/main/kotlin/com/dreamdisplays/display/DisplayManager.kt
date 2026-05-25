package com.dreamdisplays.display

import java.util.*
import java.util.concurrent.ConcurrentHashMap

private fun DisplayScreen.toFullDisplayData() = DisplaySettings.FullDisplayData(
    uuid, pos.x, pos.y, pos.z, facing, width, height,
    videoUrl ?: "", lang ?: "", volume, quality, brightness,
    muted, isSync, ownerUuid, renderDistance, currentTimeNanos,
)

/** Manager for all screen displays. */
object DisplayManager {
    val screens = ConcurrentHashMap<UUID, DisplayScreen>()
    val unloadedScreens = ConcurrentHashMap<UUID, DisplaySettings.FullDisplayData>()

    fun getScreens(): Collection<DisplayScreen> = screens.values

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
    }

    fun unregisterScreen(displayScreen: DisplayScreen) {
        unloadedScreens[displayScreen.uuid] = displayScreen.toFullDisplayData()
        screens.remove(displayScreen.uuid)
        displayScreen.unregister()
    }

    fun unloadAll() {
        screens.values.forEach { it.unregister() }
        screens.clear()
        unloadedScreens.clear()
    }

    fun saveScreenData(displayScreen: DisplayScreen) {
        DisplaySettings.saveDisplayData(displayScreen.uuid, displayScreen.toFullDisplayData())
    }

    fun loadScreensForServer(serverId: String) {
        DisplaySettings.loadServerDisplays(serverId)
    }

    fun saveAllScreens() {
        screens.values.forEach { saveScreenData(it) }
    }
}
