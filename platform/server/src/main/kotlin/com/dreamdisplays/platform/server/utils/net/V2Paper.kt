package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.api.capability.ServerFeature
import com.dreamdisplays.api.playback.FullscreenAckAction
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
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
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
    private val logger = LoggerFactory.getLogger("DreamDisplays/PaperV2Networking")
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
        isAdmin = player.hasPermission(PaperServer.config.permissions.delete),
        isReportingEnabled = PaperServer.config.settings.webhookUrl.isNotEmpty(),
        allowedFeatures = ServerFeature.playbackFeatureWires,
        defaultVolume = PaperServer.config.settings.defaultVolume,
    )

    /** Decodes an envelope frame and dispatches the packet; unknown type ids are skipped. */
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != V2_CHANNEL) return
        val packet = runCatching { PacketRegistry.decode(message, PacketDirection.CLIENT_TO_SERVER) }
            .onFailure { logger.warn("Failed to decode v2 packet from ${player.name}", it) }
            .getOrNull() ?: return

        when (packet) {
            is ClientHello -> handleHello(player, packet)
            is RequestSync -> DisplayActions.requestSync(player, packet.id)
            is DisplayDelete -> DisplayActions.delete(player, packet.id)
            is ReportDisplay -> DisplayManager.report(packet.id, player)
            is SetVideo -> DisplayActions.setVideo(player, packet.id, packet.url, packet.lang)
            is SetLocked -> DisplayActions.setLocked(player, packet.id, packet.locked)
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

            else -> logger.debug("Ignoring non-serverbound v2 packet {}.", packet::class.simpleName)
        }
    }

    /**
     * Marks [player] as a v2 peer, replies with the [ServerHello] and the display batch, and runs
     * the shared version / update bookkeeping. The legacy `version` packet that follows the hello
     * is then reduced to the update checks only (see [PacketReceiver]). Fullscreen re-delivery also
     * has to wait for this point — sending it from the raw join event races the handshake, since
     * `sendTo` / `sendDisplayInfo` are gated on [V2PlayerTracker.isV2], which isn't true yet there.
     */
    private fun handleHello(player: Player, hello: ClientHello) {
        if (V2PlayerTracker.isV2(player.uniqueId)) return
        V2PlayerTracker.markV2(player.uniqueId, hello)
        send(listOf(player), buildServerHello(player))
        DisplayActions.recordVersionAndCheckUpdates(player, hello.modVersion)
        DisplayActions.sendAllDisplays(player)
        FullscreenBroadcastManager.onPlayerJoin(player.uniqueId)
    }
}
