package com.dreamdisplays.listeners

import com.dreamdisplays.Main.Companion.config
import com.dreamdisplays.managers.DisplayManager
import com.dreamdisplays.managers.DisplayManager.getDisplays
import com.dreamdisplays.managers.PlayerManager
import com.dreamdisplays.managers.PlayerManager.hasBeenNotifiedAboutModRequired
import com.dreamdisplays.managers.PlayerManager.setModRequiredNotified
import com.dreamdisplays.utils.MessageUtil.sendMessage
import com.dreamdisplays.utils.PlatformUtil.isFolia
import com.dreamdisplays.meta.Scheduler
import com.dreamdisplays.utils.net.PacketUtils
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.jspecify.annotations.NullMarked

@NullMarked
@Suppress("UNUSED")
class PlayerListener : Listener {

    private var hasValidatedWorld = false

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        if (!hasValidatedWorld && getDisplays().isNotEmpty()) {
            hasValidatedWorld = true
            Scheduler.runLater(40L) {
                val removedDisplayUuids = DisplayManager.validateDisplaysAndCleanup()
                if (removedDisplayUuids.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    PacketUtils.sendClearCache(Bukkit.getOnlinePlayers().toMutableList(), removedDisplayUuids)
                }
            }
        }

        if (!config.settings.modDetectionEnabled) return
        if (getDisplays().isEmpty()) return

        // TODO: implement Folia-compatible entity scheduler for delayed player tasks
        if (isFolia) return

        Scheduler.runLater(600L) {
            if (PlayerManager.getVersion(player) == null && !hasBeenNotifiedAboutModRequired(player)) {
                sendMessage(player, "modRequired")
                setModRequiredNotified(player, true)
            }
        }
    }

    @EventHandler
    fun onPlayerLeave(event: PlayerQuitEvent) {
        PlayerManager.removeVersion(event.player)
    }
}
