package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.platform.client.Focuser
import com.dreamdisplays.platform.client.core.ClientApplication
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.api.runtime.getOrNull
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.util.DreamCoroutines

/**
 * Handles client shutdown cleanup.
 */
object ClientShutdownManager {
    /** Stops the application, saves and unloads screens, shuts down coroutines, and interrupts the focuser. */
    fun stop() {
        DreamServices.registry.getOrNull<ClientApplication>()?.stop()
        DisplayRegistry.saveAllScreens()
        ClientStartupManager.stop()
        DreamCoroutines.shutdown()
        DisplayRegistry.unloadAll()
        Focuser.instance?.interrupt()
    }
}
