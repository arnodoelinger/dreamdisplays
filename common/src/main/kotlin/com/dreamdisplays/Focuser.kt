package com.dreamdisplays

import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.managers.ClientStateManager
import net.minecraft.client.Minecraft

/** Background thread that temporarily mutes / unmutes screens based on window focus. */
class Focuser : Thread() {
    init {
        isDaemon = true
        instance = this
        name = "window-focus-mute-thread"
    }

    /** Polls window focus every 250 ms and mutes or unmutes all screens when `mute-on-alt-tab` is enabled. */
    override fun run() {
        while (true) {
            if (ClientStateManager.config.muteOnAltTab) {
                val mc: Minecraft? = runCatching { Minecraft.getInstance() }.getOrNull()
                if (mc != null) {
                    val focused = mc.isWindowActive
                    for (screen in DisplayRegistry.getScreens()) {
                        screen.setFocusMuted(!focused)
                    }
                }
            }
            try {
                sleep(250)
            } catch (_: InterruptedException) {
                currentThread().interrupt()
                break
            }
        }
    }

    companion object {
        @Volatile var instance: Focuser? = null
            private set
    }
}
