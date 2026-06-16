package com.dreamdisplays.server.listeners

import com.dreamdisplays.server.Main.Companion.config
import com.dreamdisplays.server.Server
import com.dreamdisplays.server.managers.DisplayManager
import com.dreamdisplays.server.managers.PlayerManager
import com.dreamdisplays.server.meta.Scheduler
import com.dreamdisplays.server.playback.WatchPartyManager
import com.dreamdisplays.server.utils.MessageUtil
import com.dreamdisplays.server.utils.PlatformUtil
import com.dreamdisplays.server.utils.net.FabricPacketUtil
import com.dreamdisplays.server.utils.net.PacketUtil
import com.dreamdisplays.server.utils.net.V2PlayerTracker
import com.dreamdisplays.server.utils.net.ServerScheduler
import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.jspecify.annotations.NullMarked

/**
 * Handles player join and leave events. If mod detection is enabled, schedules a delayed `modRequired` message for
 * vanilla clients.
 */
@Suppress("UNUSED")
@PaperOnly @NullMarked class PlayerListener : Listener {
    private var hasValidatedWorld = false

    /**
     * On the first join after startup, validates all stored displays once. Also schedules a delayed
     * `modRequired` message for vanilla clients when mod detection is enabled.
     */
    @EventHandler fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        if (!hasValidatedWorld && DisplayManager.getDisplays().isNotEmpty()) {
            hasValidatedWorld = true
            Scheduler.runLater(40L) {
                val removedDisplayUuids = DisplayManager.validateDisplaysAndCleanup()
                if (removedDisplayUuids.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    PacketUtil.sendClearCache(Bukkit.getOnlinePlayers().toMutableList(), removedDisplayUuids)
                }
            }
        }

        if (!config.settings.modDetectionEnabled) return
        if (DisplayManager.getDisplays().isEmpty()) return

        // TODO: implement Folia-compatible entity scheduler for delayed player tasks
        if (PlatformUtil.isFolia) return

        Scheduler.runLater(600L) {
            if (PlayerManager.getVersion(player) == null && !PlayerManager.hasBeenNotifiedAboutModRequired(player)) {
                MessageUtil.sendMessage(player, "modRequired")
                PlayerManager.setModRequiredNotified(player, true)
            }
        }
    }

    /** Drops cached per-player state when a player disconnects. */
    @EventHandler fun onPlayerLeave(event: PlayerQuitEvent) {
        PlayerManager.removeVersion(event.player)
        V2PlayerTracker.clear(event.player.uniqueId)
        WatchPartyManager.onPlayerQuit(event.player.uniqueId)
    }
}

/**
 * `Fabric` specific implementation of [PlayerListener].
 */
@FabricOnly object FabricPlayerListener {
    private var hasValidatedWorld = false

    /**
     * On the first join after startup, validates all stored displays once. Also schedules a delayed
     * `modRequired` message for vanilla clients when mod detection is enabled.
     */
    fun register() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val player = handler.player

            if (!hasValidatedWorld && DisplayManager.getDisplays().isNotEmpty()) {
                hasValidatedWorld = true
                ServerScheduler.runLater(server, 40L) {
                    val removedUuids = DisplayManager.validateDisplaysAndCleanup(server)
                    if (removedUuids.isNotEmpty()) {
                        FabricPacketUtil.sendClearCache(server.playerList.players, removedUuids)
                    }
                }
            }

            val config = Server.config
            if (!config.settings.modDetectionEnabled) return@register
            if (DisplayManager.getDisplays().isEmpty()) return@register

            ServerScheduler.runLater(server, 600L) {
                if (player.isAlive &&
                    PlayerManager.getVersion(player) == null &&
                    !PlayerManager.hasBeenNotifiedAboutModRequired(player)
                ) {
                    MessageUtil.sendMessage(player, "modRequired")
                    PlayerManager.setModRequiredNotified(player, true)
                }
            }
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            PlayerManager.removeVersion(handler.player)
            V2PlayerTracker.clear(handler.player.uuid)
            WatchPartyManager.onPlayerQuit(handler.player.uuid)
        }
    }
}
