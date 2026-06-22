package com.dreamdisplays.managers

import com.dreamdisplays.Focuser
import com.dreamdisplays.client.core.ClientApplication
import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.utils.DreamCoroutines

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
