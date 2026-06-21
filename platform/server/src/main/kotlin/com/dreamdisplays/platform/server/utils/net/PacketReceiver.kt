package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.platform.server.Main
import com.dreamdisplays.platform.server.datatypes.SyncData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import io.github.arsmotorin.ofrat.PaperOnly
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.*

/**
 * Frozen protocol v1 — the wire format of these channels must never change. Decodes legacy
 * plugin messages from pre-v2 clients and delegates to the shared [DisplayActions] logic.
 * New packets go to the v2 channel handled by [PaperV2Networking].
 */
@Deprecated("Protocol v1 receiver; remove when v1 client support is dropped.")
@PaperOnly @NullMarked class PacketReceiver(private val plugin: Main) : PluginMessageListener {
    private val logger = LoggerFactory.getLogger("DreamDisplays/PacketReceiver")
    private val maxVersionBytes = 128
    private val maxStringBytes = 4096

    /** Routes an incoming plugin message to the per-channel handler. */
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        when (channel) {
            "dreamdisplays:sync" -> handleSyncPacket(player, message)
            "dreamdisplays:req_sync" -> handleRequestSync(player, message)
            "dreamdisplays:delete" -> handleDelete(player, message)
            "dreamdisplays:report" -> handleReport(player, message)
            "dreamdisplays:version" -> handleVersion(player, message)
            "dreamdisplays:display_enabled" -> handleDisplayEnabled(player, message)
            "dreamdisplays:set_video" -> handleSetVideo(player, message)
            "dreamdisplays:set_locked" -> handleSetLocked(player, message)
        }
    }

    /** Decodes a sync packet from [player] and forwards it to [StateManager.processSyncPacket]. */
    private fun handleSyncPacket(player: Player, message: ByteArray) {
        runCatching {
            DataInputStream(ByteArrayInputStream(message)).use { input ->
                val syncData = SyncData(
                    input.readUUID(),
                    input.readBoolean(),
                    input.readBoolean(),
                    input.readVarLong(),
                    input.readVarLong()
                )
                StateManager.processSyncPacket(syncData, player)
            }
        }.onFailure { e ->
            logger.warn("Failed to decode sync packet", e)
        }
    }

    /** Replies to a client `req_sync` packet with the current authoritative sync state. */
    private fun handleRequestSync(player: Player, message: ByteArray) {
        readUUIDPacket(message)?.let { displayId ->
            StateManager.sendSyncPacket(displayId, player)
        }
    }

    /** Handles a client-requested deletion, enforcing owner-or-permission check. */
    private fun handleDelete(player: Player, message: ByteArray) {
        readUUIDPacket(message)?.let { displayId ->
            DisplayActions.delete(player, displayId)
        }
    }

    /** Forwards a client report request to [DisplayManager.report]. */
    private fun handleReport(player: Player, message: ByteArray) {
        readUUIDPacket(message)?.let { displayId ->
            DisplayManager.report(displayId, player)
        }
    }

    /**
     * Handles the legacy handshake. For v2 players the [PaperV2Networking] hello already did the
     * version bookkeeping and the flag / display sends, so the duplicate is suppressed here.
     */
    private fun handleVersion(player: Player, message: ByteArray) {
        runCatching {
            val version = readVersionString(message)
            if (V2PlayerTracker.isV2(player.uniqueId)) return

            DisplayActions.recordVersionAndCheckUpdates(player, version)
            PacketUtil.sendPremium(player, player.hasPermission(Main.config.permissions.premium))
            PacketUtil.sendIsAdmin(player, player.hasPermission(Main.config.permissions.delete))
            PacketUtil.sendReportEnabled(player, Main.config.settings.webhookUrl.isNotEmpty())
            DisplayActions.sendAllDisplays(player)
        }.onFailure { e ->
            logger.warn("Failed to process version packet", e)
        }
    }

    /** Persists a client toggle for whether [player] wants to render displays. */
    private fun handleDisplayEnabled(player: Player, message: ByteArray) {
        runCatching {
            DataInputStream(ByteArrayInputStream(message)).use { input ->
                val enabled = input.readBoolean()
                PlayerManager.setDisplaysEnabled(player, enabled)
            }
        }.onFailure { e ->
            logger.warn("Failed to decode display enabled packet", e)
        }
    }

    /** Applies a client-supplied URL / language to a display via [DisplayActions.setVideo]. */
    private fun handleSetVideo(player: Player, message: ByteArray) {
        runCatching {
            DataInputStream(ByteArrayInputStream(message)).use { input ->
                val displayId = input.readUUID()
                val url = input.readString()
                val lang = input.readString()
                DisplayActions.setVideo(player, displayId, url, lang)
            }
        }.onFailure { e ->
            logger.warn("Failed to decode set_video packet", e)
        }
    }

    /** Updates the locked flag of a display via [DisplayActions.setLocked]. */
    private fun handleSetLocked(player: Player, message: ByteArray) {
        runCatching {
            DataInputStream(ByteArrayInputStream(message)).use { input ->
                val displayId = input.readUUID()
                val locked = input.readBoolean()
                DisplayActions.setLocked(player, displayId, locked)
            }
        }.onFailure { e ->
            logger.warn("Failed to decode set_locked packet", e)
        }
    }

    /** Reads a single UUID payload, logging and returning null on decode failure. */
    private fun readUUIDPacket(message: ByteArray): UUID? {
        return runCatching {
            DataInputStream(ByteArrayInputStream(message)).use { input ->
                input.readUUID()
            }
        }.onFailure { e ->
            logger.error("Failed to decode UUID packet", e)
        }.getOrNull()
    }

    /** Reads a length-prefixed version string with strict bounds checks to reject malformed payloads. */
    private fun readVersionString(message: ByteArray): String {
        return DataInputStream(ByteArrayInputStream(message)).use { input ->
            val length = input.readVarInt()
            require(length in 1..maxVersionBytes) {
                "Invalid version packet length: $length."
            }
            require(length <= input.available()) {
                "Invalid version packet size: declared = $length, but available = ${input.available()}."
            }
            val data = ByteArray(length)
            input.readFully(data)
            String(data, 0, length)
        }
    }

    /** Reads a 128-bit UUID using the shared encoding in [PacketUtil]. */
    private fun DataInputStream.readUUID() = PacketUtil.run { readUUID() }

    /** Reads a Minecraft-style VarInt using the shared encoding in [PacketUtil]. */
    private fun DataInputStream.readVarInt() = PacketUtil.run { readVarInt() }

    /** Reads a Minecraft-style VarLong using the shared encoding in [PacketUtil]. */
    private fun DataInputStream.readVarLong() = PacketUtil.run { readVarLong() }

    /** Reads a UTF-8 string prefixed by its byte length as a VarInt, with the same bounds checks as [readVersionString]. */
    private fun DataInputStream.readString(): String {
        val length = readVarInt()
        require(length in 0..maxStringBytes) {
            "Invalid string packet length: $length."
        }
        require(length <= available()) {
            "Invalid string packet size. Declared = $length, but available = ${available()}."
        }
        val data = ByteArray(length)
        readFully(data)
        return String(data, Charsets.UTF_8)
    }
}
