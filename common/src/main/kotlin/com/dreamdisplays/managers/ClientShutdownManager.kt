package com.dreamdisplays.managers

import com.dreamdisplays.Focuser
import com.dreamdisplays.client.core.ClientApplication
import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.displays.DisplayRegistry

/**
 * Handles client shutdown cleanup.
 */
object ClientShutdownManager {
    fun stop() {
        DreamServices.registry.getOrNull<ClientApplication>()?.stop()
        DisplayRegistry.saveAllScreens()
        ClientStateManager.qualityRefreshThread.interrupt()
        DisplayRegistry.unloadAll()
        Focuser.instance?.interrupt()
    }
}
