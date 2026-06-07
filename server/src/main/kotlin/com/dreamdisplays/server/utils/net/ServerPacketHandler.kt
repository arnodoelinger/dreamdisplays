package com.dreamdisplays.server.utils.net

import com.dreamdisplays.net.Packets
import com.dreamdisplays.server.Server
import com.dreamdisplays.server.datatypes.FabricDisplayData
import com.dreamdisplays.server.datatypes.SyncData
import com.dreamdisplays.server.managers.DisplayManager
import com.dreamdisplays.server.managers.PlayerManager
import com.dreamdisplays.server.managers.StateManager
import com.dreamdisplays.server.utils.MessageUtil
import com.dreamdisplays.server.utils.YouTubeUtil
import io.github.arsmotorin.ofrat.FabricOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
//? if >=1.21.11 {
import net.minecraft.server.players.NameAndId
//?}
import org.semver4j.Semver
import org.slf4j.LoggerFactory

/**
 * Fabric-specific implementation of packet receivers.
 */
@FabricOnly object ServerPacketHandler {
    private val logger = LoggerFactory.getLogger("DreamDisplays/PacketReceiver")

    /** Registers all packet receivers for Fabric servers. */
    fun registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(Packets.Version.PACKET_ID) { payload, context ->
            val player = context.player()
            val server = context.server()
            val version = payload.version
            logger.info("${player.name.string} joined with Dream Displays $version.")

            runCatching {
                val parsedVersion = parseVersionOrNull(version)
                PlayerManager.setVersion(player, parsedVersion)

                val config = Server.config
                FabricPacketUtil.sendPremium(player, isOpLevel2(player))
                FabricPacketUtil.sendIsAdmin(player, isOpLevel2(player))
                FabricPacketUtil.sendReportEnabled(player, config.settings.webhookUrl.isNotEmpty())

                //? if >=1.21.11 {
                val playerWorldKey = player.level().dimension().identifier().toString()
                //?} else
                /*val playerWorldKey = player.level().dimension().location().toString()*/
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
                StateManager.processSyncPacket(syncData, player, server)
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
            val player = context.player()
            runCatching {
                val displayId = payload.uuid
                val displayData = DisplayManager.getDisplayData(displayId) as? FabricDisplayData
                    ?: return@registerGlobalReceiver MessageUtil.sendMessage(player, "noDisplay")

                if (displayData.ownerId != player.uuid && !isOpLevel2(player)) {
                    MessageUtil.sendMessage(player, "displayCommandMissingPermission")
                    return@registerGlobalReceiver
                }

                val receivers = DisplayManager.getReceivers(displayData, context.server())
                DisplayManager.delete(displayData)
                FabricPacketUtil.sendDelete(receivers, displayId)
                MessageUtil.sendMessage(player, "displayDeleted")
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
            val player = context.player()
            runCatching {
                val displayId = payload.uuid
                val displayData = DisplayManager.getDisplayData(displayId) as? FabricDisplayData
                    ?: return@registerGlobalReceiver

                if (displayData.isLocked && displayData.ownerId != player.uuid && !isOpLevel2(player)) return@registerGlobalReceiver

                val wasSync = displayData.isSync
                displayData.url = payload.url
                displayData.lang = payload.lang

                val receivers = DisplayManager.getReceivers(displayData, context.server())
                FabricPacketUtil.sendDisplayInfo(receivers, displayData)
                if (wasSync) StateManager.resetAndBroadcast(displayId, receivers)
            }.onFailure { e ->
                logger.warn("Failed to handle set_video packet", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.SetLocked.PACKET_ID) { payload, context ->
            val player = context.player()
            runCatching {
                val displayData = DisplayManager.getDisplayData(payload.uuid) as? FabricDisplayData
                    ?: return@registerGlobalReceiver MessageUtil.sendMessage(player, "noDisplay")

                if (displayData.ownerId != player.uuid && !isOpLevel2(player)) {
                    MessageUtil.sendMessage(player, "displayCommandMissingPermission")
                    return@registerGlobalReceiver
                }

                displayData.isLocked = payload.locked
                Server.storage?.saveDisplay(displayData)

                val receivers = DisplayManager.getReceivers(displayData, context.server())
                FabricPacketUtil.sendDisplayInfo(receivers, displayData)
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
        val server = player.level().server ?: return false
        return server.playerList.isOp(
            //? if >=1.21.11 {
            NameAndId(player.gameProfile)
            //?} else
            /*player.gameProfile*/
        )
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
