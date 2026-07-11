package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.api.capability.ServerFeature
import com.dreamdisplays.api.playback.FullscreenAckAction
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.net.V2Payload
import com.dreamdisplays.core.protocol.ClientHello
import com.dreamdisplays.core.protocol.DisplayDelete
import com.dreamdisplays.core.protocol.DreamPacket
import com.dreamdisplays.core.protocol.FullscreenAck
import com.dreamdisplays.api.protocol.PacketDirection
import com.dreamdisplays.core.protocol.PacketRegistry
import com.dreamdisplays.api.playback.PlaybackAction
import com.dreamdisplays.core.protocol.PlaybackCommand
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.core.protocol.ReportDisplay
import com.dreamdisplays.core.protocol.RequestSync
import com.dreamdisplays.core.protocol.ServerHello
import com.dreamdisplays.core.protocol.SetDisplaysEnabled
import com.dreamdisplays.core.protocol.SetLocked
import com.dreamdisplays.core.protocol.SetMode
import com.dreamdisplays.core.protocol.SetVideo
import com.dreamdisplays.api.playback.WatchPartyAction
import com.dreamdisplays.core.protocol.WatchPartyControl
import com.dreamdisplays.core.protocol.WatchPartyStart
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.utils.RegionUtil
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import org.slf4j.LoggerFactory

/**
 * Protocol-v2 networking for the `NeoForge` flavor.
 */
@NeoForgeOnly
object NeoForgeV2Networking {
    private val logger = LoggerFactory.getLogger("DreamDisplays/NeoForgeV2Networking")

    /** Encodes [packet] once and sends it to every player in [players]. */
    fun send(players: List<ServerPlayer>, packet: DreamPacket) {
        if (players.isEmpty()) return
        val bytes = runCatching { PacketRegistry.encode(packet) }
            .onFailure { logger.warn("Failed to encode v2 packet", it) }
            .getOrNull() ?: return
        players.forEach { player ->
            runCatching { PacketDistributor.sendToPlayer(player, V2Payload(bytes)) }
        }
    }

    /**
     * Registers the single v2 envelope receiver against [registrar]. Must be called exactly once
     * total for the whole mod (NeoForge's payload registry rejects a second registration of the
     * same id) - see `NeoForgeServer.registerPayloads` for why this can't also be registered from
     * `Client`.
     */
    fun registerReceivers(registrar: PayloadRegistrar) {
        //? if >=1.21.11 {
        registrar.playBidirectional(
            V2Payload.TYPE, V2Payload.CODEC,
            { payload, context ->
                runCatching {
                    val player = context.player() as ServerPlayer
                    dispatch(
                        player,
                        RegionUtil.playerServer(player),
                        PacketRegistry.decode(payload.bytes, PacketDirection.CLIENT_TO_SERVER) ?: return@runCatching,
                    )
                }.onFailure { e -> logger.warn("Failed to handle v2 packet", e) }
            },
            clientHandler { payload, _ -> Initializer.onV2Packet(payload.bytes) },
        )
        //?} else
        /*registrar.playBidirectional(V2Payload.TYPE, V2Payload.CODEC) { payload, context ->
            if (context.flow() == net.minecraft.network.protocol.PacketFlow.SERVERBOUND) {
                runCatching {
                    val player = context.player() as ServerPlayer
                    dispatch(
                        player,
                        RegionUtil.playerServer(player),
                        PacketRegistry.decode(payload.bytes, PacketDirection.CLIENT_TO_SERVER) ?: return@runCatching,
                    )
                }.onFailure { e -> logger.warn("Failed to handle v2 packet", e) }
            } else if (isClientDist) {
                Initializer.onV2Packet(payload.bytes)
            }
        }*/
    }

    /** Routes a decoded serverbound packet to the shared action handlers. */
    private fun dispatch(player: ServerPlayer, server: MinecraftServer, packet: DreamPacket) {
        when (packet) {
            is ClientHello -> handleHello(player, server, packet)
            is RequestSync -> VanillaDisplayActions.requestSync(player, packet.id)
            is DisplayDelete -> VanillaDisplayActions.delete(player, server, packet.id)
            is ReportDisplay -> DisplayManager.report(packet.id, player, server)
            is SetVideo -> VanillaDisplayActions.setVideo(player, server, packet.id, packet.url, packet.lang)
            is SetLocked -> VanillaDisplayActions.setLocked(player, server, packet.id, packet.locked)
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

            is SetDisplaysEnabled -> PlayerManager.setDisplaysEnabled(player.uuid, packet.enabled)
            is FullscreenAck -> FullscreenBroadcastManager.handleAck(
                packet.sessionId, player.uuid, FullscreenAckAction.fromWire(packet.action),
            )

            else -> logger.debug("Ignoring non-serverbound v2 packet {}.", packet::class.simpleName)
        }
    }

    /** Marks [player] as a v2 peer, replies with the [ServerHello] and the display batch. */
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
            ),
        )
        VanillaDisplayActions.recordVersionAndCheckUpdates(player, hello.modVersion)
        VanillaDisplayActions.sendAllDisplays(player, server)
    }
}
