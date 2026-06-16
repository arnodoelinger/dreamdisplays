package com.dreamdisplays.server.utils.net

import com.dreamdisplays.net.Packets
import com.dreamdisplays.protocol.PlaybackAction
import com.dreamdisplays.protocol.PlaybackMode
import com.dreamdisplays.protocol.PlaybackPermissions
import com.dreamdisplays.protocol.WatchPartyAction
import com.dreamdisplays.server.Server
import com.dreamdisplays.server.datatypes.FabricDisplayData
import com.dreamdisplays.server.datatypes.SyncData
import com.dreamdisplays.server.managers.DisplayManager
import com.dreamdisplays.server.managers.PlayerManager
import com.dreamdisplays.server.managers.StateManager
import com.dreamdisplays.server.playback.PlaybackContexts
import com.dreamdisplays.server.playback.TimelineManager
import com.dreamdisplays.server.playback.WatchPartyManager
import com.dreamdisplays.server.utils.MessageUtil
import com.dreamdisplays.server.utils.YouTubeUtil
import io.github.arsmotorin.ofrat.FabricOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId
import org.semver4j.Semver
import org.slf4j.LoggerFactory

/**
 * Fabric-specific packet actions, shared between the frozen v1 receivers registered here and the
 * protocol-v2 dispatch in [FabricV2Networking].
 */
@FabricOnly object ServerPacketHandler {
    private val logger = LoggerFactory.getLogger("DreamDisplays/PacketReceiver")

    /** Records the player's reported mod version and runs the mod / plugin update checks. */
    fun recordVersionAndCheckUpdates(player: ServerPlayer, version: String) {
        logger.info("${player.name.string} joined with Dream Displays $version.")
        val parsedVersion = parseVersionOrNull(version)
        PlayerManager.setVersion(player, parsedVersion)

        val config = Server.config
        val modLatest = Server.modLatestVersion
        if (modLatest != null && parsedVersion != null && parsedVersion < modLatest &&
            !PlayerManager.hasBeenNotifiedAboutModUpdate(player)
        ) {
            val msg = config.getMessageForPlayer(player, "newVersion")
            MessageUtil.sendColoredMessage(player, msg?.let { String.format(it.toString(), modLatest.toString()) })
            PlayerManager.setModUpdateNotified(player, true)
        }

        if (config.settings.updatesEnabled &&
            !PlayerManager.hasBeenNotifiedAboutPluginUpdate(player)
        ) {
            val latestPlugin = Server.pluginLatestVersion
            val currentVersion = Server.serverVersion
            if (latestPlugin != null && currentVersion != null &&
                !currentVersion.contains("-SNAPSHOT", ignoreCase = true)
            ) {
                val current = Semver.coerce(currentVersion)
                val latest = Semver.coerce(latestPlugin)
                if (current != null && latest != null && current < latest) {
                    val msg = config.getMessageForPlayer(player, "newPluginVersion") as? String
                    if (msg != null) {
                        MessageUtil.sendColoredMessage(player, String.format(msg, latestPlugin))
                        PlayerManager.setPluginUpdateNotified(player, true)
                    }
                }
            }
        }
    }

    /** Streams every display in [player]'s world to them in small staggered batches. */
    fun sendAllDisplays(player: ServerPlayer, server: MinecraftServer) {
        val playerWorldKey = player.level().dimension().identifier().toString()
        val displays = DisplayManager.getDisplays()
            .filterIsInstance<FabricDisplayData>()
            .filter { it.worldKey == playerWorldKey }

        val batchSize = 5
        displays.chunked(batchSize).forEachIndexed { index, batch ->
            if (index == 0) {
                batch.forEach { FabricPacketUtil.sendDisplayInfo(listOf(player), it) }
            } else {
                val delayTicks = (index * 2).toLong()
                ServerScheduler.runLater(server, delayTicks) {
                    if (player.isAlive) {
                        batch.forEach { FabricPacketUtil.sendDisplayInfo(listOf(player), it) }
                    }
                }
            }
        }
    }

    /** Handles a client-requested deletion, enforcing owner-or-permission check. */
    fun delete(player: ServerPlayer, server: MinecraftServer, displayId: java.util.UUID) {
        val displayData = DisplayManager.getDisplayData(displayId) as? FabricDisplayData
            ?: return MessageUtil.sendMessage(player, "noDisplay")

        if (displayData.ownerId != player.uuid && !isOpLevel2(player)) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return
        }

        val receivers = DisplayManager.getReceivers(displayData, server)
        DisplayManager.delete(displayData)
        FabricPacketUtil.sendDelete(receivers, displayId)
        MessageUtil.sendMessage(player, "displayDeleted")
    }

    /** Applies a client-supplied URL / language to a display, broadcasting and resetting the timeline. */
    fun setVideo(player: ServerPlayer, server: MinecraftServer, displayId: java.util.UUID, url: String, lang: String) {
        val displayData = DisplayManager.getDisplayData(displayId) as? FabricDisplayData ?: return
        if (!PlaybackPermissions.canSetVideo(context(displayData, player))) return

        val wasSync = displayData.isSync
        displayData.url = url
        displayData.lang = lang

        val receivers = DisplayManager.getReceivers(displayData, server)
        FabricPacketUtil.sendDisplayInfo(receivers, displayData)
        if (wasSync) StateManager.resetAndBroadcast(displayId, receivers) // frozen-v1 clock
        TimelineManager.onVideoChanged(displayData)
    }

    /** Updates the locked flag of a display owned by [player] and rebroadcasts. */
    fun setLocked(player: ServerPlayer, server: MinecraftServer, displayId: java.util.UUID, locked: Boolean) {
        val displayData = DisplayManager.getDisplayData(displayId) as? FabricDisplayData
            ?: return MessageUtil.sendMessage(player, "noDisplay")
        if (!PlaybackPermissions.canToggleLock(context(displayData, player))) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return
        }

        displayData.isLocked = locked
        Server.storage?.saveDisplay(displayData)

        val receivers = DisplayManager.getReceivers(displayData, server)
        FabricPacketUtil.sendDisplayInfo(receivers, displayData)
    }

    /** Switches a display's persistent base mode (`LOCAL` / `SYNCED` / `BROADCAST`) and re-anchors its clock. */
    fun setMode(player: ServerPlayer, server: MinecraftServer, displayId: java.util.UUID, mode: PlaybackMode, positionMs: Long) {
        if (!PlaybackMode.isBaseMode(mode)) return
        val displayData = DisplayManager.getDisplayData(displayId) as? FabricDisplayData ?: return
        if (!PlaybackPermissions.canSetMode(context(displayData, player))) return

        displayData.mode = mode
        Server.storage?.saveDisplay(displayData)
        FabricPacketUtil.sendDisplayInfo(DisplayManager.getReceivers(displayData, server), displayData)
        TimelineManager.onModeChanged(displayData, positionMs)
    }

    /** Applies a playback intent (play / pause / seek / restart) to a `SYNCED` display's server clock. */
    fun playbackCommand(player: ServerPlayer, displayId: java.util.UUID, action: PlaybackAction, positionMs: Long) {
        val displayData = DisplayManager.getDisplayData(displayId) as? FabricDisplayData ?: return
        TimelineManager.onCommand(displayData, player.uuid, action, positionMs)
    }

    /** Starts a watch-party session with [player] as host. */
    fun watchPartyStart(player: ServerPlayer, displayId: java.util.UUID, url: String, lang: String) {
        val displayData = DisplayManager.getDisplayData(displayId) as? FabricDisplayData ?: return
        WatchPartyManager.start(displayData, player.uuid, url, lang)
    }

    /** Routes a watch-party control (ready / host action) to the session manager. */
    fun watchPartyControl(player: ServerPlayer, displayId: java.util.UUID, action: WatchPartyAction, positionMs: Long) {
        val displayData = DisplayManager.getDisplayData(displayId) as? FabricDisplayData ?: return
        WatchPartyManager.control(displayData, player.uuid, action, positionMs)
    }

    /** Replies to a client's catch-up request with the current timeline and any live session. */
    fun requestSync(player: ServerPlayer, displayId: java.util.UUID) {
        val displayData = DisplayManager.getDisplayData(displayId) ?: return
        TimelineManager.sendCurrent(displayData, player.uuid)
        WatchPartyManager.sendCurrent(displayData, player.uuid)
    }

    /** Builds the permission context for [player] acting on [display]. */
    private fun context(display: FabricDisplayData, player: ServerPlayer) =
        PlaybackContexts.of(display, player.uuid, isOpLevel2(player))

    /** Registers all frozen v1 packet receivers for Fabric servers. */
    @Deprecated("Protocol v1 receivers; remove when v1 client support is dropped.")
    fun registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(Packets.Version.PACKET_ID) { payload, context ->
            val player = context.player()
            val server = context.server()

            runCatching {
                // v2 players already got the hello reply / flags / display batch.
                if (V2PlayerTracker.isV2(player.uuid)) return@runCatching

                recordVersionAndCheckUpdates(player, payload.version)
                FabricPacketUtil.sendPremium(player, isOpLevel2(player))
                FabricPacketUtil.sendIsAdmin(player, isOpLevel2(player))
                FabricPacketUtil.sendReportEnabled(player, Server.config.settings.webhookUrl.isNotEmpty())
                sendAllDisplays(player, server)
            }.onFailure { e ->
                logger.warn("Failed to process version packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.Sync.PACKET_ID) { payload, context ->
            val player = context.player()
            val server = context.server()
            val syncData = SyncData(
                id = payload.uuid,
                isSync = payload.isSync,
                currentState = payload.currentState,
                currentTime = payload.currentTime,
                limitTime = payload.limitTime
            )
            runCatching {
                StateManager.processSyncPacket(syncData, player, server, isOpLevel2(player))
            }.onFailure { e ->
                logger.warn("Failed to handle sync packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.RequestSync.PACKET_ID) { payload, context ->
            runCatching {
                StateManager.sendSyncPacket(payload.uuid, context.player())
            }.onFailure { e ->
                logger.warn("Failed to handle request_sync packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.Delete.PACKET_ID) { payload, context ->
            runCatching {
                delete(context.player(), context.server(), payload.uuid)
            }.onFailure { e ->
                logger.warn("Failed to handle delete packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.Report.PACKET_ID) { payload, context ->
            runCatching {
                DisplayManager.report(payload.uuid, context.player(), context.server())
            }.onFailure { e ->
                logger.warn("Failed to handle report packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.DisplayEnabled.PACKET_ID) { payload, context ->
            runCatching {
                PlayerManager.setDisplaysEnabled(context.player(), payload.enabled)
            }.onFailure { e ->
                logger.warn("Failed to handle display_enabled packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.SetVideo.PACKET_ID) { payload, context ->
            runCatching {
                setVideo(context.player(), context.server(), payload.uuid, payload.url, payload.lang)
            }.onFailure { e ->
                logger.warn("Failed to handle set_video packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.SetLocked.PACKET_ID) { payload, context ->
            runCatching {
                setLocked(context.player(), context.server(), payload.uuid, payload.locked)
            }.onFailure { e ->
                logger.warn("Failed to handle set_locked packet", e)
            }
        }
    }

    /** Sanitizes [raw] and coerces it into a [Semver], returning null if parsing fails. */
    private fun parseVersionOrNull(raw: String): Semver? {
        val sanitized = YouTubeUtil.sanitize(raw)?.takeIf { it.isNotEmpty() } ?: return null
        return Semver.coerce(sanitized)
    }

    /** Checks if [player] has operator level 2 permissions, which is the threshold for privileged actions. */
    fun isOpLevel2(player: ServerPlayer): Boolean {
        return player.level().server.playerList.isOp(NameAndId(player.gameProfile))
    }
}

/** Simple scheduler helper for server-side delayed tasks. */
@FabricOnly object ServerScheduler {
    fun runLater(server: MinecraftServer, delayTicks: Long, task: Runnable) {
        if (delayTicks <= 0L) {
            server.execute(task)
            return
        }
        Thread({
            runCatching {
                Thread.sleep(delayTicks * 50L)
                server.execute(task)
            }
        }, "dreamdisplays-delayed-task").also { it.isDaemon = true }.start()
    }
}
