package com.dreamdisplays.server.utils.net

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
import com.dreamdisplays.server.Main
import com.dreamdisplays.server.managers.DisplayManager
import com.dreamdisplays.server.managers.PlayerManager
import io.github.arsmotorin.ofrat.PaperOnly
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
@PaperOnly @NullMarked object PaperV2Networking : PluginMessageListener {
    private val logger = LoggerFactory.getLogger("DreamDisplays/PaperV2Networking")
    private val plugin: Main by lazy { Main.getInstance() }

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
        isPremium = player.hasPermission(Main.config.permissions.premium),
        isAdmin = player.hasPermission(Main.config.permissions.delete),
        isReportingEnabled = Main.config.settings.webhookUrl.isNotEmpty(),
        allowedFeatures = PLAYBACK_FEATURES,
    )

    /** Decodes an envelope frame and dispatches the packet; unknown type ids are skipped. */
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != V2_CHANNEL) return
        val packet = runCatching { PacketRegistry.decode(message) }
            .onFailure { logger.warn("Failed to decode v2 packet from ${player.name}", it) }
            .getOrNull() ?: return

        when (packet) {
            is ClientHello -> handleHello(player, packet)
            is RequestSync -> DisplayActions.requestSync(player, packet.id)
            is DisplayDelete -> DisplayActions.delete(player, packet.id)
            is ReportDisplay -> DisplayManager.report(packet.id, player)
            is SetVideo -> DisplayActions.setVideo(player, packet.id, packet.url, packet.lang)
            is SetLocked -> DisplayActions.setLocked(player, packet.id, packet.locked)
            is SetMode -> DisplayActions.setMode(player, packet.id, PlaybackMode.fromWire(packet.mode), packet.positionMs)
            is PlaybackCommand -> PlaybackAction.fromWire(packet.action)?.let {
                DisplayActions.playbackCommand(player, packet.id, it, packet.positionMs)
            }
            is WatchPartyStart -> DisplayActions.watchPartyStart(player, packet.id, packet.url, packet.lang)
            is WatchPartyControl -> WatchPartyAction.fromWire(packet.action)?.let {
                DisplayActions.watchPartyControl(player, packet.id, it, packet.positionMs)
            }
            is SetDisplaysEnabled -> PlayerManager.setDisplaysEnabled(player, packet.enabled)
            else -> logger.debug("Ignoring non-serverbound v2 packet {}.", packet::class.simpleName)
        }
    }

    /** v2 feature flags advertised to clients so they only surface modes / parties on capable servers. */
    private val PLAYBACK_FEATURES = listOf("modes", "watch_party", "broadcast")

    /**
     * Marks [player] as a v2 peer, replies with the [ServerHello] and the display batch, and runs
     * the shared version / update bookkeeping. The legacy `version` packet that follows the hello
     * is then reduced to the update checks only (see [PacketReceiver]).
     */
    private fun handleHello(player: Player, hello: ClientHello) {
        V2PlayerTracker.markV2(player.uniqueId, hello)
        send(listOf(player), buildServerHello(player))
        DisplayActions.recordVersionAndCheckUpdates(player, hello.modVersion)
        DisplayActions.sendAllDisplays(player)
    }
}
