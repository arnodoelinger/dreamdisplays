package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.api.capability.ServerFeature
import com.dreamdisplays.api.playback.model.FullscreenAckAction
import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.WatchPartyAction
import com.dreamdisplays.api.protocol.model.PacketDirection
import com.dreamdisplays.core.protocol.common.PacketRegistry
import com.dreamdisplays.core.protocol.common.packets.*
import com.dreamdisplays.platform.client.net.V2Payload
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.PipPinManager
import com.dreamdisplays.platform.server.proxy.VanillaProxyBridge
import io.github.arnodoelinger.platformweaver.FabricOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

/**
 * Protocol-v2 networking for the `Fabric` flavor: one envelope payload in both directions.
 * Business logic is shared with the frozen-v1 receivers through [VanillaDisplayActions].
 */
@FabricOnly
object FabricV2Networking {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Encodes [packet] once and sends it to every player in [players]. */
    fun send(players: List<ServerPlayer>, packet: DreamPacket) {
        if (players.isEmpty()) return
        val bytes = runCatching { PacketRegistry.encode(packet) }
            .onFailure { logger.warn("Failed to encode v2 packet", it) }
            .getOrNull() ?: return
        players.forEach { player ->
            runCatching { ServerPlayNetworking.send(player, V2Payload(bytes)) }
        }
    }

    /** Registers the single v2 envelope receiver. */
    fun registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(V2Payload.TYPE) { payload, context ->
            runCatching {
                dispatch(
                    context.player(),
                    context.server(),
                    PacketRegistry.decode(payload.bytes, PacketDirection.CLIENT_TO_SERVER) ?: return@runCatching,
                )
            }.onFailure { e ->
                logger.warn("Failed to handle v2 packet", e)
            }
        }
    }

    /** Routes a decoded serverbound packet to the shared action handlers. */
    private fun dispatch(player: ServerPlayer, server: MinecraftServer, packet: DreamPacket) {
        when (packet) {
            is ClientHello -> handleHello(player, server, packet)
            is RequestSync -> VanillaDisplayActions.requestSync(player, packet.id)
            is ReportDuration -> VanillaDisplayActions.reportDuration(player, packet.id, packet.durationMs)
            is DisplayDelete -> VanillaDisplayActions.delete(player, server, packet.id)
            is ReportDisplay -> DisplayManager.report(packet.id, player, server)
            is SetVideo -> VanillaDisplayActions.setVideo(player, server, packet.id, packet.url, packet.lang)
            is SetLocked -> VanillaDisplayActions.setAccess(player, server, packet.id, packet.accessLevel())
            is SetMode -> VanillaDisplayActions.setMode(
                player,
                server,
                packet.id,
                PlaybackMode.fromWire(packet.mode),
                packet.positionMs
            )

            is PlaybackCommand -> PlaybackAction.fromWire(packet.action)?.let {
                VanillaDisplayActions.playbackCommand(player, packet.id, it, packet.positionMs)
            }

            is WatchPartyStart -> VanillaDisplayActions.watchPartyStart(player, packet.id, packet.url, packet.lang)
            is WatchPartyControl -> WatchPartyAction.fromWire(packet.action)?.let {
                VanillaDisplayActions.watchPartyControl(player, packet.id, it, packet.positionMs)
            }

            is SetDisplaysEnabled -> PlayerManager.setDisplaysEnabled(player, packet.enabled)
            is FullscreenAck -> FullscreenBroadcastManager.handleAck(
                packet.sessionId, player.uuid, FullscreenAckAction.fromWire(packet.action),
            )

            is PipPin -> if (packet.pinned) {
                PipPinManager.pin(player.uuid, packet.id)
            } else {
                PipPinManager.unpin(player.uuid, packet.id)
            }

            else -> logger.debug("Ignoring non-serverbound v2 packet {}.", packet::class.simpleName)
        }
    }

    /**
     * Marks [player] as a v2 peer, replies with the [ServerHello] and the display batch. Fullscreen re-delivery also
     * runs here for reconnecting viewers.
     */
    private fun handleHello(player: ServerPlayer, server: MinecraftServer, hello: ClientHello) {
        if (V2PlayerTracker.isV2(player.uuid)) return
        V2PlayerTracker.markV2(player.uuid, hello)
        send(
            listOf(player),
            ServerHello(
                isPremium = VanillaDisplayActions.isPremium(player),
                isAdmin = VanillaDisplayActions.isAdmin(player),
                isReportingEnabled = VanillaServerState.config.settings.webhookUrl.isNotEmpty(),
                allowedFeatures = ServerFeature.playbackFeatureWires,
                defaultVolume = VanillaServerState.config.settings.defaultVolume,
                maxDisplays = VanillaDisplayActions.maxDisplaysFor(player),
            ),
        )
        VanillaDisplayActions.recordVersionAndCheckUpdates(player, hello.modVersion)
        VanillaDisplayActions.sendAllDisplays(player, server)
        FullscreenBroadcastManager.onPlayerJoin(player.uuid)
        PipPinManager.onPlayerJoin(player.uuid)
        VanillaProxyBridge.onPlayerReady(player, server)
    }
}
