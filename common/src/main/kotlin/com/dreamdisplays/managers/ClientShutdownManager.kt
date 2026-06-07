package com.dreamdisplays.managers

import com.dreamdisplays.Focuser
import com.dreamdisplays.display.DisplayManager

/**
 * Handles client shutdown cleanup.
 */
object ClientShutdownManager {
    fun stop() {
        DisplayManager.saveAllScreens()
        ClientStateManager.qualityRefreshThread.interrupt()
        DisplayManager.unloadAll()
        Focuser.instance?.interrupt()
    }
}
