package com.dreamdisplays.managers

import com.dreamdisplays.Main
import com.dreamdisplays.Main.Companion.config
import com.dreamdisplays.managers.SelectionManager.selectionPoints
import com.dreamdisplays.utils.PlatformUtil.isFolia
import com.dreamdisplays.utils.OutlinerUtil.showOutline
import org.bukkit.Bukkit

object SelectionVisualizer {
    fun startParticleTask(plugin: Main) {
        if (!config.settings.particlesEnabled) return
        if (isFolia) return

        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            selectionPoints.forEach { (playerId, sel) ->
                if (!sel.isReady) return@forEach
                val player = Bukkit.getPlayer(playerId) ?: return@forEach
                val p1 = sel.pos1 ?: return@forEach
                val p2 = sel.pos2 ?: return@forEach
                showOutline(player, p1, p2)
            }
        }, 0L, config.settings.particleRenderDelay.toLong())
    }
}
