package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.net.Packets
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.SyncData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions.delete
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions.isOpLevel2
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions.recordVersionAndCheckUpdates
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions.sendAllDisplays
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions.setLocked
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions.setVideo
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import org.slf4j.LoggerFactory

/**
 * Registers the frozen protocol-v1 packet receivers for `Fabric` and `NeoForge`. Business logic is
 * shared by both loaders through [VanillaDisplayActions]; only the two [registerReceivers] overloads
 * here are loader-specific, since Fabric's and NeoForge's payload-registration APIs are unrelated.
 */
object VanillaServerPacketHandler {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/PacketReceiver")

    /** Registers all frozen v1 packet receivers for `Fabric` servers. */
    @FabricOnly
    @Deprecated("Protocol v1 receivers; remove when v1 client support is dropped.")
    fun registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(Packets.Version.PACKET_ID) { payload, context ->
            val player = context.player()
            val server = context.server()

            runCatching {
                if (V2PlayerTracker.isV2(player.uuid)) return@runCatching

                recordVersionAndCheckUpdates(player, payload.version)
                VanillaPacketUtil.sendPremium(player, isOpLevel2(player))
                VanillaPacketUtil.sendIsAdmin(player, isOpLevel2(player))
                VanillaPacketUtil.sendReportEnabled(player, VanillaServerState.config.settings.webhookUrl.isNotEmpty())
                sendAllDisplays(player, server)
            }.onFailure { e ->
                logger.warn("Failed to process version packet.", e)
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
                logger.warn("Failed to handle sync packet.", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.RequestSync.PACKET_ID) { payload, context ->
            runCatching {
                StateManager.sendSyncPacket(payload.uuid, context.player())
            }.onFailure { e ->
                logger.warn("Failed to handle request_sync packet.", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.Delete.PACKET_ID) { payload, context ->
            runCatching {
                delete(context.player(), context.server(), payload.uuid)
            }.onFailure { e ->
                logger.warn("Failed to handle delete packet.", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.Report.PACKET_ID) { payload, context ->
            runCatching {
                DisplayManager.report(payload.uuid, context.player(), context.server())
            }.onFailure { e ->
                logger.warn("Failed to handle report packet.", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.DisplayEnabled.PACKET_ID) { payload, context ->
            runCatching {
                PlayerManager.setDisplaysEnabled(context.player().uuid, payload.enabled)
            }.onFailure { e ->
                logger.warn("Failed to handle display_enabled packet.", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.SetVideo.PACKET_ID) { payload, context ->
            runCatching {
                setVideo(context.player(), context.server(), payload.uuid, payload.url, payload.lang)
            }.onFailure { e ->
                logger.warn("Failed to handle set_video packet.", e)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(Packets.SetLocked.PACKET_ID) { payload, context ->
            runCatching {
                setLocked(context.player(), context.server(), payload.uuid, payload.locked)
            }.onFailure { e ->
                logger.warn("Failed to handle set_locked packet.", e)
            }
        }
    }

    /**
     * Registers all frozen v1 packets against [registrar]. Must be called exactly once total for
     * the whole mod (`NeoForge`'s payload registry rejects a second registration of the same id) —
     * see `NeoForgeServer.registerPayloads` for why this can't also be registered from `Client`.
     */
    @NeoForgeOnly
    @Deprecated("Protocol v1 receivers; remove when v1 client support is dropped.")
    fun registerReceivers(registrar: PayloadRegistrar) {
        registrar.playToServer(Packets.Version.PACKET_ID, Packets.Version.PACKET_CODEC) { payload, context ->
            val player = context.player() as ServerPlayer
            val server = RegionUtil.playerServer(player)

            runCatching {
                if (V2PlayerTracker.isV2(player.uuid)) return@runCatching

                recordVersionAndCheckUpdates(player, payload.version)
                VanillaPacketUtil.sendPremium(player, isOpLevel2(player))
                VanillaPacketUtil.sendIsAdmin(player, isOpLevel2(player))
                VanillaPacketUtil.sendReportEnabled(player, VanillaServerState.config.settings.webhookUrl.isNotEmpty())
                sendAllDisplays(player, server)
            }.onFailure { e ->
                logger.warn("Failed to process version packet.", e)
            }
        }

        registrar.playBidirectionalCompat(
            Packets.Sync.PACKET_ID, Packets.Sync.PACKET_CODEC,
            { payload, context ->
                val player = context.player() as ServerPlayer
                val server = RegionUtil.playerServer(player)
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
                    logger.warn("Failed to handle sync packet.", e)
                }
            },
            clientHandler { payload, _ -> Initializer.onLegacyPacket(payload) },
        )

        registrar.playToServer(Packets.RequestSync.PACKET_ID, Packets.RequestSync.PACKET_CODEC) { payload, context ->
            runCatching {
                StateManager.sendSyncPacket(payload.uuid, context.player() as ServerPlayer)
            }.onFailure { e ->
                logger.warn("Failed to handle request_sync packet.", e)
            }
        }

        registrar.playBidirectionalCompat(
            Packets.Delete.PACKET_ID, Packets.Delete.PACKET_CODEC,
            { payload, context ->
                runCatching {
                    val player = context.player() as ServerPlayer
                    delete(player, RegionUtil.playerServer(player), payload.uuid)
                }.onFailure { e ->
                    logger.warn("Failed to handle delete packet.", e)
                }
            },
            clientHandler { payload, _ -> Initializer.onLegacyPacket(payload) },
        )

        registrar.playToServer(Packets.Report.PACKET_ID, Packets.Report.PACKET_CODEC) { payload, context ->
            runCatching {
                val player = context.player() as ServerPlayer
                DisplayManager.report(payload.uuid, player, RegionUtil.playerServer(player))
            }.onFailure { e ->
                logger.warn("Failed to handle report packet.", e)
            }
        }

        registrar.playBidirectionalCompat(
            Packets.DisplayEnabled.PACKET_ID, Packets.DisplayEnabled.PACKET_CODEC,
            { payload, context ->
                runCatching {
                    PlayerManager.setDisplaysEnabled((context.player() as ServerPlayer).uuid, payload.enabled)
                }.onFailure { e ->
                    logger.warn("Failed to handle display_enabled packet.", e)
                }
            },
            clientHandler { payload, _ -> Initializer.onLegacyPacket(payload) },
        )

        registrar.playToServer(Packets.SetVideo.PACKET_ID, Packets.SetVideo.PACKET_CODEC) { payload, context ->
            runCatching {
                val player = context.player() as ServerPlayer
                setVideo(player, RegionUtil.playerServer(player), payload.uuid, payload.url, payload.lang)
            }.onFailure { e ->
                logger.warn("Failed to handle set_video packet.", e)
            }
        }

        registrar.playToServer(Packets.SetLocked.PACKET_ID, Packets.SetLocked.PACKET_CODEC) { payload, context ->
            runCatching {
                val player = context.player() as ServerPlayer
                setLocked(player, RegionUtil.playerServer(player), payload.uuid, payload.locked)
            }.onFailure { e ->
                logger.warn("Failed to handle set_locked packet.", e)
            }
        }

        registrar.playToClient(Packets.Info.PACKET_ID, Packets.Info.PACKET_CODEC) { payload, _ ->
            Initializer.onLegacyPacket(payload)
        }
        registrar.playToClient(Packets.Premium.PACKET_ID, Packets.Premium.PACKET_CODEC) { payload, _ ->
            Initializer.onLegacyPacket(payload)
        }
        registrar.playToClient(Packets.IsAdmin.PACKET_ID, Packets.IsAdmin.PACKET_CODEC) { payload, _ ->
            Initializer.onLegacyPacket(payload)
        }
        registrar.playToClient(Packets.ReportEnabled.PACKET_ID, Packets.ReportEnabled.PACKET_CODEC) { payload, _ ->
            Initializer.onLegacyPacket(payload)
        }
        registrar.playToClient(Packets.ClearCache.PACKET_ID, Packets.ClearCache.PACKET_CODEC) { payload, _ ->
            Initializer.onLegacyPacket(payload)
        }
    }
}
