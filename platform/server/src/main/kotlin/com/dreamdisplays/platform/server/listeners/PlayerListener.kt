package com.dreamdisplays.platform.server.listeners

import com.dreamdisplays.platform.server.PaperServer.Companion.config
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.PlatformUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.net.VanillaServerScheduler
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.jspecify.annotations.NullMarked
import java.util.UUID

/**
 * Handles player join and leave events. If mod detection is enabled, schedules a delayed `modRequired` message for
 * vanilla clients.
 */
@Suppress("UNUSED")
@PaperOnly
@NullMarked
class PlayerListener : Listener {
    /** Tracks whether the world has been validated after startup. */
    private var hasValidatedWorld = false

    /**
     * On the first join after startup, validates all stored displays once. Also schedules a delayed
     * `modRequired` message for vanilla clients when mod detection is enabled.
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        Scheduler.trackPlayer(player)
        WatchPartyManager.onPlayerJoin(player.uniqueId)

        if (!PlatformUtil.isFolia && !hasValidatedWorld && DisplayManager.getDisplays().isNotEmpty()) {
            hasValidatedWorld = true
            Scheduler.runLater(40L) { DisplayManager.validateDisplaysAndCleanup() }
        }

        if (!config.settings.modDetectionEnabled) return
        if (DisplayManager.getDisplays().isEmpty()) return

        Scheduler.runPlayerLater(player, 600L) {
            if (PlayerManager.getVersion(player) == null && !PlayerManager.hasBeenNotifiedAboutModRequired(player)) {
                MessageUtil.sendMessage(player, "modRequired")
                PlayerManager.setModRequiredNotified(player, true)
            }
        }
    }

    /** Drops cached per-player state when a player disconnects. */
    @EventHandler
    fun onPlayerLeave(event: PlayerQuitEvent) {
        PlayerManager.removeVersion(event.player)
        V2PlayerTracker.clear(event.player.uniqueId)
        WatchPartyManager.onPlayerQuit(event.player.uniqueId)
        DisplayManager.forgetNearbyPlayer(event.player.uniqueId)
        Scheduler.untrackPlayer(event.player)
    }
}

/**
 * Shared `Fabric` / `NeoForge` player join / leave handling. [FabricPlayerListener] and
 * [NeoForgePlayerListener] only adapt their loader's event-subscription API and hand off here.
 */
object VanillaPlayerListener {
    private var hasValidatedWorld = false

    /**
     * On the first join after startup, validates all stored displays once. Also schedules a delayed
     * `modRequired` message for vanilla clients when mod detection is enabled.
     */
    fun onJoin(player: ServerPlayer, server: MinecraftServer) {
        WatchPartyManager.onPlayerJoin(player.uuid)
        if (!hasValidatedWorld && DisplayManager.getDisplays().isNotEmpty()) {
            hasValidatedWorld = true
            VanillaServerScheduler.runLater(server, 40L) { DisplayManager.validateDisplaysAndCleanup(server) }
        }

        if (!VanillaServerState.config.settings.modDetectionEnabled) return
        if (DisplayManager.getDisplays().isEmpty()) return

        VanillaServerScheduler.runLater(server, 600L) {
            if (player.isAlive &&
                PlayerManager.getVersion(player.uuid) == null &&
                !PlayerManager.hasBeenNotifiedAboutModRequired(player.uuid)
            ) {
                MessageUtil.sendMessage(player, "modRequired")
                PlayerManager.setModRequiredNotified(player.uuid, true)
            }
        }
    }

    /** Drops cached per-player state when a player disconnects. */
    fun onLeave(playerId: UUID) {
        PlayerManager.removePlayer(playerId)
        V2PlayerTracker.clear(playerId)
        WatchPartyManager.onPlayerQuit(playerId)
    }
}

/** `Fabric` event-subscription adapter for [VanillaPlayerListener]. */
@FabricOnly
object FabricPlayerListener {
    fun register() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            VanillaPlayerListener.onJoin(handler.player, server)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            VanillaPlayerListener.onLeave(handler.player.uuid)
        }
    }
}

/** `NeoForge` event-subscription adapter for [VanillaPlayerListener]. */
@NeoForgeOnly
object NeoForgePlayerListener {
    @SubscribeEvent
    fun onLogin(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        VanillaPlayerListener.onJoin(player, RegionUtil.playerServer(player))
    }

    @SubscribeEvent
    fun onLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        VanillaPlayerListener.onLeave(player.uuid)
    }
}
