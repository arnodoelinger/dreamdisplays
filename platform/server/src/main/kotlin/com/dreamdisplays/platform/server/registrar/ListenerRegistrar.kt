package com.dreamdisplays.platform.server.registrar

import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.platform.server.Main
import com.dreamdisplays.platform.server.listeners.PlayerListener
import com.dreamdisplays.platform.server.listeners.ProtectionListener
import com.dreamdisplays.platform.server.listeners.SelectionListener
import org.bukkit.Bukkit

/**
 * Registers event listeners.
 */
@PaperOnly
object ListenerRegistrar {
    /** Registers selection, protection, and player listeners with `Bukkit`. */
    fun registerListeners(plugin: Main) {
        val pm = Bukkit.getPluginManager()
        pm.registerEvents(SelectionListener(plugin), plugin)
        pm.registerEvents(ProtectionListener(), plugin)
        pm.registerEvents(PlayerListener(), plugin)
    }
}
