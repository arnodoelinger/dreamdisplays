package com.dreamdisplays.server.utils.net

import com.dreamdisplays.net.V2Payload
import com.dreamdisplays.protocol.ClientHello
import com.dreamdisplays.protocol.DisplayDelete
import com.dreamdisplays.protocol.DreamPacket
import com.dreamdisplays.protocol.PacketRegistry
import com.dreamdisplays.protocol.PlaybackAction
import com.dreamdisplays.protocol.PlaybackCommand
import com.dreamdisplays.protocol.PlaybackMode
import com.dreamdisplays.protocol.ReportDisplay
import com.dreamdisplays.protocol.RequestSync
import com.dreamdisplays.protocol.ServerHello
import com.dreamdisplays.protocol.SetDisplaysEnabled
import com.dreamdisplays.protocol.SetLocked
import com.dreamdisplays.protocol.SetMode
import com.dreamdisplays.protocol.SetVideo
import com.dreamdisplays.protocol.WatchPartyAction
import com.dreamdisplays.protocol.WatchPartyControl
import com.dreamdisplays.protocol.WatchPartyStart
import com.dreamdisplays.server.Server
import com.dreamdisplays.server.managers.DisplayManager
import com.dreamdisplays.server.managers.PlayerManager
import io.github.arsmotorin.ofrat.FabricOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

/**
 * Protocol-v2 networking for the `Fabric` flavor: one envelope payload in both directions.
 * Business logic is shared with the frozen-v1 receivers through [ServerPacketHandler].
 */
@FabricOnly object FabricV2Networking {
    private val logger = LoggerFactory.getLogger("DreamDisplays/FabricV2Networking")

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
                dispatch(context.player(), context.server(), PacketRegistry.decode(payload.bytes) ?: return@runCatching)
            }.onFailure { e ->
                logger.warn("Failed to handle v2 packet", e)
            }
        }
    }

    /** Routes a decoded serverbound packet to the shared action handlers. */
    private fun dispatch(player: ServerPlayer, server: MinecraftServer, packet: DreamPacket) {
        when (packet) {
            is ClientHello -> handleHello(player, server, packet)
            is RequestSync -> ServerPacketHandler.requestSync(player, packet.id)
            is DisplayDelete -> ServerPacketHandler.delete(player, server, packet.id)
            is ReportDisplay -> DisplayManager.report(packet.id, player, server)
            is SetVideo -> ServerPacketHandler.setVideo(player, server, packet.id, packet.url, packet.lang)
            is SetLocked -> ServerPacketHandler.setLocked(player, server, packet.id, packet.locked)
            is SetMode -> ServerPacketHandler.setMode(player, server, packet.id, PlaybackMode.fromWire(packet.mode), packet.positionMs)
            is PlaybackCommand -> PlaybackAction.fromWire(packet.action)?.let {
                ServerPacketHandler.playbackCommand(player, packet.id, it, packet.positionMs)
            }
            is WatchPartyStart -> ServerPacketHandler.watchPartyStart(player, packet.id, packet.url, packet.lang)
            is WatchPartyControl -> WatchPartyAction.fromWire(packet.action)?.let {
                ServerPacketHandler.watchPartyControl(player, packet.id, it, packet.positionMs)
            }
            is SetDisplaysEnabled -> PlayerManager.setDisplaysEnabled(player, packet.enabled)
            else -> logger.debug("Ignoring non-serverbound v2 packet {}.", packet::class.simpleName)
        }
    }

    /** v2 feature flags advertised to clients so they only surface modes / parties on capable servers. */
    private val PLAYBACK_FEATURES = listOf("modes", "watch_party", "broadcast")

    /** Marks [player] as a v2 peer, replies with the [ServerHello] and the display batch. */
    private fun handleHello(player: ServerPlayer, server: MinecraftServer, hello: ClientHello) {
        V2PlayerTracker.markV2(player.uuid, hello)
        send(
            listOf(player),
            ServerHello(
                isPremium = ServerPacketHandler.isOpLevel2(player),
                isAdmin = ServerPacketHandler.isOpLevel2(player),
                isReportingEnabled = Server.config.settings.webhookUrl.isNotEmpty(),
                allowedFeatures = PLAYBACK_FEATURES,
            ),
        )
        ServerPacketHandler.recordVersionAndCheckUpdates(player, hello.modVersion)
        ServerPacketHandler.sendAllDisplays(player, server)
    }
}
