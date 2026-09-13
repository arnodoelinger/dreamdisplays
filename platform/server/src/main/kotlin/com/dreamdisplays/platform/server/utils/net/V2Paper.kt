package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.api.capability.ServerFeature
import com.dreamdisplays.api.playback.model.FullscreenAckAction
import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.WatchPartyAction
import com.dreamdisplays.api.protocol.model.PacketDirection
import com.dreamdisplays.core.protocol.common.PacketRegistry
import com.dreamdisplays.core.protocol.common.packets.*
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.PipPinManager
import com.dreamdisplays.platform.server.proxy.ProxyBridge
import com.dreamdisplays.platform.server.utils.WorldGuardRegions
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory

/** The single protocol-v2 plugin-message channel. */
const val V2_CHANNEL: String = "dreamdisplays:v2"

/**
 * Protocol-v2 networking for the Paper flavor: receives envelope frames on [V2_CHANNEL], answers
 * the [ClientHello] handshake, and sends v2 packets to negotiated players. Business logic is
 * shared with the frozen-v1 path through [DisplayActions].
 */
@PaperOnly
@NullMarked
object PaperV2Networking : PluginMessageListener {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val plugin: PaperServer by lazy { PaperServer.getInstance() }

    /** Encodes [packet] once and sends it to every non-null player in [players]. */
    fun send(players: List<Player?>, packet: DreamPacket) {
        val bytes = runCatching { PacketRegistry.encode(packet) }
            .onFailure { logger.warn("Failed to encode v2 packet", it) }
            .getOrNull() ?: return
        players.filterNotNull().forEach { player ->
            runCatching { player.sendPluginMessage(plugin, V2_CHANNEL, bytes) }
                .onFailure { logger.warn("Failed to send v2 packet to ${player.name}", it) }
        }
    }

    /** The capability snapshot for [player], rebuilt from permissions and config. */
    fun buildServerHello(player: Player): ServerHello = ServerHello(
        isPremium = player.hasPermission(PaperServer.config.permissions.premium),
        isAdmin = player.hasPermission(PaperServer.config.permissions.deleteOthers),
        isReportingEnabled = PaperServer.config.settings.webhookUrl.isNotEmpty(),
        allowedFeatures = serverFeatureWires(),
        defaultVolume = PaperServer.config.settings.defaultVolume,
        maxDisplays = maxDisplaysFor(player.hasPermission(PaperServer.config.permissions.createBypass)),
    )

    /**
     * The feature tokens for this server: the unconditional playback set, plus region access only
     * where `WorldGuard` is actually installed to answer membership questions.
     */
    private fun serverFeatureWires(): List<String> =
        if (WorldGuardRegions.isAvailable()) {
            ServerFeature.playbackFeatureWires + ServerFeature.REGION_ACCESS.wire
        } else {
            ServerFeature.playbackFeatureWires
        }

    /** [ServerHello.maxDisplays] for a player: `-1` (unlimited) when [hasBypass] or no cap is configured. */
    private fun maxDisplaysFor(hasBypass: Boolean): Int {
        val cap = PaperServer.config.settings.maxDisplaysPerPlayer
        return if (hasBypass || cap <= 0) -1 else cap
    }

    /** Decodes an envelope frame and dispatches the packet; unknown type ids are skipped. */
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != V2_CHANNEL) return
        val packet = runCatching { PacketRegistry.decode(message, PacketDirection.CLIENT_TO_SERVER) }
            .onFailure { logger.warn("Failed to decode v2 packet from ${player.name}", it) }
            .getOrNull() ?: return

        when (packet) {
            is ClientHello -> handleHello(player, packet)
            is RequestSync -> DisplayActions.requestSync(player, packet.id)
            is ReportDuration -> DisplayActions.reportDuration(player, packet.id, packet.durationMs)
            is DisplayDelete -> DisplayActions.delete(player, packet.id)
            is ReportDisplay -> DisplayManager.report(packet.id, player)
            is SetVideo -> DisplayActions.setVideo(player, packet.id, packet.url, packet.lang)
            is SetLocked -> DisplayActions.setAccess(player, packet.id, packet.accessLevel())
            is SetMode -> DisplayActions.setMode(
                player,
                packet.id,
                PlaybackMode.fromWire(packet.mode),
                packet.positionMs
            )

            is PlaybackCommand -> PlaybackAction.fromWire(packet.action)?.let {
                DisplayActions.playbackCommand(player, packet.id, it, packet.positionMs)
            }

            is WatchPartyStart -> DisplayActions.watchPartyStart(player, packet.id, packet.url, packet.lang)
            is WatchPartyControl -> WatchPartyAction.fromWire(packet.action)?.let {
                DisplayActions.watchPartyControl(player, packet.id, it, packet.positionMs)
            }

            is SetDisplaysEnabled -> PlayerManager.setDisplaysEnabled(player, packet.enabled)
            is FullscreenAck -> FullscreenBroadcastManager.handleAck(
                packet.sessionId, player.uniqueId, FullscreenAckAction.fromWire(packet.action),
            )

            is PipPin -> if (packet.pinned) {
                PipPinManager.pin(player.uniqueId, packet.id)
            } else {
                PipPinManager.unpin(player.uniqueId, packet.id)
            }

            else -> logger.debug("Ignoring non-serverbound v2 packet {}.", packet::class.simpleName)
        }
    }

    /**
     * Marks [player] as a v2 peer, replies with the [ServerHello] and the display batch, and runs the shared
     * version-check flow.
     */
    private fun handleHello(player: Player, hello: ClientHello) {
        if (V2PlayerTracker.isV2(player.uniqueId)) return
        V2PlayerTracker.markV2(player.uniqueId, hello)
        send(listOf(player), buildServerHello(player))
        DisplayActions.recordVersionAndCheckUpdates(player, hello.modVersion)
        DisplayActions.sendAllDisplays(player)
        FullscreenBroadcastManager.onPlayerJoin(player.uniqueId)
        PipPinManager.onPlayerJoin(player.uniqueId)
        ProxyBridge.onPlayerReady(player)
    }
}
