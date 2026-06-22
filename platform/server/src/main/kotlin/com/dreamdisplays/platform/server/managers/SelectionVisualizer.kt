package com.dreamdisplays.platform.server.managers

import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.platform.server.Main
import com.dreamdisplays.platform.server.Main.Companion.config
import com.dreamdisplays.platform.server.datatypes.PaperSelectionData
import com.dreamdisplays.platform.server.managers.SelectionManager.selectionPoints
import com.dreamdisplays.platform.server.utils.OutlinerUtil.showOutline
import com.dreamdisplays.platform.server.utils.PlatformUtil.isFolia
import org.bukkit.Bukkit

/**
 * Draws particle outlines around active player selections on a repeating scheduler tick,
 * giving visual feedback while the player is making a display selection.
 */
@PaperOnly
object SelectionVisualizer {
    /**
     * Starts a repeating task that draws particle outlines around every ready selection.
     * No-ops if particles are disabled in config or the server is `Folia` (unsupported there).
     */
    fun startParticleTask(plugin: Main) {
        if (!config.settings.particlesEnabled) return
        if (isFolia) return

        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            selectionPoints.forEach { (playerId, sel) ->
                if (sel !is PaperSelectionData || !sel.isReady) return@forEach
                val player = Bukkit.getPlayer(playerId) ?: return@forEach
                val p1 = sel.pos1 ?: return@forEach
                val p2 = sel.pos2 ?: return@forEach
                showOutline(player, p1, p2)
            }
        }, 0L, config.settings.particleRenderDelay.toLong())
    }
}
