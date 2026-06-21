package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.platform.client.Focuser
import com.dreamdisplays.platform.client.core.ClientApplication
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.core.getOrNull
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.util.DreamCoroutines

/**
 * Handles client shutdown cleanup.
 */
object ClientShutdownManager {
    fun stop() {
        DreamServices.registry.getOrNull<ClientApplication>()?.stop()
        DisplayRegistry.saveAllScreens()
        ClientStartupManager.stop()
        DreamCoroutines.shutdown()
        DisplayRegistry.unloadAll()
        Focuser.instance?.interrupt()
    }
}
