package com.dreamdisplays.server.utils.net

import com.dreamdisplays.net.Packets
import com.dreamdisplays.Server
import com.dreamdisplays.server.datatypes.SyncData
import com.dreamdisplays.server.managers.*
import com.dreamdisplays.server.utils.MessageUtil
import com.dreamdisplays.server.utils.YouTubeUtil
import com.github.zafarkhaja.semver.Version
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId
import org.slf4j.LoggerFactory

/**
 * Different from `Paper` implementation.
 *
 * Registers and handles all incoming (serverbound) packets.
 *
 * All per-packet business logic (checks, state updates, batch sending) is identical with `Paper`
 * But in `Fabric server` instead of `PluginMessageListener.onPluginMessageReceived()` dispatch,
 * each packet type gets its own `ServerPlayNetworking.registerGlobalReceiver()` call.
 *
 * Player and server are extracted from the context object instead of direct parameters.
 *
 * `Fabric server` implementation.
 */
object ServerPacketHandler {
    // Paper start
    private val logger = LoggerFactory.getLogger("DreamDisplays/PacketReceiver")

    fun registerReceivers() {
        // Fabric server start
        ServerPlayNetworking.registerGlobalReceiver(Packets.Version.PACKET_ID) { payload, context ->
            val player = context.player()
            val server = context.server()
            // Fabric server end
            val versionString = payload.version
            logger.info("[PacketReceiver] ${player.name.string} joined with Dream Displays $versionString")

            runCatching {
                val version = parseVersionOrNull(versionString)
                PlayerManager.setVersion(player, version)

                val config = Server.config
                // Fabric server start
                PacketUtil.sendPremium(player, isOpLevel2(player))
                PacketUtil.sendReportEnabled(player, config.settings.webhookUrl.isNotEmpty())

                val playerWorldKey = player.level().dimension().identifier().toString()
                // Fabric server end
                val displays = DisplayManager.getDisplays()
                    .filter { it.worldKey == playerWorldKey }

                val batchSize = 5
                displays.chunked(batchSize).forEachIndexed { index, batch ->
                    if (index == 0) {
                        batch.forEach { PacketUtil.sendDisplayInfo(listOf(player), it) }
                    } else {
                        val delayTicks = (index * 2).toLong()
                        // Fabric server start
                        ServerScheduler.runLater(server, delayTicks) {
                            if (player.isAlive) {
                                batch.forEach { PacketUtil.sendDisplayInfo(listOf(player), it) }
                            }
                        }
                        // Fabric server end
                    }
                }

                val modLatest = Server.modLatestVersion
                if (modLatest != null && version != null && version < modLatest &&
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
                        val cur = runCatching { Version.parse(currentVersion) }.getOrNull()
                        val lat = runCatching { Version.parse(latestPlugin) }.getOrNull()
                        if (cur != null && lat != null && cur < lat) {
                            val msg = config.getMessageForPlayer(player, "newPluginVersion") as? String
                            if (msg != null) {
                                MessageUtil.sendColoredMessage(player, String.format(msg, latestPlugin))
                                PlayerManager.setPluginUpdateNotified(player, true)
                            }
                        }
                    }
                }
            }.onFailure { e ->
                logger.warn("[PacketReceiver] Failed to process version packet", e)
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
                StateManager.processSyncPacket(syncData, player, server)
            }.onFailure { e ->
                logger.warn("[PacketReceiver] Failed to handle sync packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.RequestSync.PACKET_ID) { payload, context ->
            runCatching {
                StateManager.sendSyncPacket(payload.uuid, context.player())
            }.onFailure { e ->
                logger.warn("[PacketReceiver] Failed to handle request_sync packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.Delete.PACKET_ID) { payload, context ->
            val player = context.player()
            runCatching {
                val displayId = payload.uuid
                val displayData = DisplayManager.getDisplayData(displayId)
                    ?: return@registerGlobalReceiver MessageUtil.sendMessage(player, "noDisplay")

                val config = Server.config
                // Fabric server start
                if (displayData.ownerId != player.uuid && !isOpLevel2(player)) {
                // Fabric server end
                    MessageUtil.sendMessage(player, "displayCommandMissingPermission")
                    return@registerGlobalReceiver
                }

                val receivers = DisplayManager.getReceivers(displayData, context.server())
                DisplayManager.delete(displayId)
                PacketUtil.sendDelete(receivers, displayId)
                MessageUtil.sendMessage(player, "displayDeleted")
            }.onFailure { e ->
                logger.warn("[PacketReceiver] Failed to handle delete packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.Report.PACKET_ID) { payload, context ->
            runCatching {
                DisplayManager.report(payload.uuid, context.player(), context.server())
            }.onFailure { e ->
                logger.warn("[PacketReceiver] Failed to handle report packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.DisplayEnabled.PACKET_ID) { payload, context ->
            runCatching {
                PlayerManager.setDisplaysEnabled(context.player(), payload.enabled)
            }.onFailure { e ->
                logger.warn("[PacketReceiver] Failed to handle display_enabled packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.SetVideo.PACKET_ID) { payload, context ->
            val player = context.player()
            runCatching {
                val displayId = payload.uuid
                val displayData = DisplayManager.getDisplayData(displayId) ?: return@registerGlobalReceiver

                // Fabric server start
                if (displayData.isLocked && displayData.ownerId != player.uuid && !isOpLevel2(player)) return@registerGlobalReceiver
                // Fabric server end

                val wasSync = displayData.isSync
                displayData.url = payload.url
                displayData.lang = payload.lang

                val receivers = DisplayManager.getReceivers(displayData, context.server())
                PacketUtil.sendDisplayInfo(receivers, displayData)
                if (wasSync) StateManager.resetAndBroadcast(displayId, receivers)
            }.onFailure { e ->
                logger.warn("[PacketReceiver] Failed to handle set_video packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.SetLocked.PACKET_ID) { payload, context ->
            val player = context.player()
            runCatching {
                val displayData = DisplayManager.getDisplayData(payload.uuid)
                    ?: return@registerGlobalReceiver MessageUtil.sendMessage(player, "noDisplay")

                // Fabric server start
                if (displayData.ownerId != player.uuid && !isOpLevel2(player)) {
                // Fabric server end
                    MessageUtil.sendMessage(player, "displayCommandMissingPermission")
                    return@registerGlobalReceiver
                }

                displayData.isLocked = payload.locked
                Server.storage?.saveDisplay(displayData)

                val receivers = DisplayManager.getReceivers(displayData, context.server())
                PacketUtil.sendDisplayInfo(receivers, displayData)
            }.onFailure { e ->
                logger.warn("[PacketReceiver] Failed to handle set_locked packet", e)
            }
        }
    }

    private fun parseVersionOrNull(raw: String): Version? {
        val sanitized = YouTubeUtil.sanitize(raw)?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Version.parse(sanitized) }.getOrNull()
    }

    // Fabric server start
    fun isOpLevel2(player: ServerPlayer): Boolean {
        return player.level().server.playerList.isOp(NameAndId(player.gameProfile))
    }
    // Fabric server end
}

/** Simple scheduler helper for server-side delayed tasks. */
object ServerScheduler {
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
    // Paper end
}
