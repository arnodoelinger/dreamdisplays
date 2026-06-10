package com.dreamdisplays.managers

import com.dreamdisplays.Focuser
import com.dreamdisplays.client.core.ClientApplication
import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.displays.DisplayManager

/**
 * Handles client shutdown cleanup.
 */
object ClientShutdownManager {
    fun stop() {
        DreamServices.registry.getOrNull<ClientApplication>()?.stop()
        DisplayManager.saveAllScreens()
        ClientStateManager.qualityRefreshThread.interrupt()
        DisplayManager.unloadAll()
        Focuser.instance?.interrupt()
    }
}
