package com.dreamdisplays.utils.net

import com.dreamdisplays.Main
import com.dreamdisplays.Main.Companion.config
import com.dreamdisplays.Main.Companion.modVersion
import com.dreamdisplays.datatypes.SyncData
import com.dreamdisplays.managers.DisplayManager.delete
import com.dreamdisplays.managers.DisplayManager.getDisplayData
import com.dreamdisplays.managers.DisplayManager.getDisplays
import com.dreamdisplays.managers.DisplayManager.getReceivers
import com.dreamdisplays.managers.DisplayManager.report
import com.dreamdisplays.managers.DisplayManager.sendUpdate
import com.dreamdisplays.managers.PlayerManager.hasBeenNotifiedAboutModUpdate
import com.dreamdisplays.managers.PlayerManager.hasBeenNotifiedAboutPluginUpdate
import com.dreamdisplays.managers.PlayerManager.setDisplaysEnabled
import com.dreamdisplays.managers.PlayerManager.setModUpdateNotified
import com.dreamdisplays.managers.PlayerManager.setPluginUpdateNotified
import com.dreamdisplays.managers.PlayerManager.setVersion
import com.dreamdisplays.managers.StateManager.processSyncPacket
import com.dreamdisplays.managers.StateManager.sendSyncPacket
import com.dreamdisplays.meta.Scheduler
import com.dreamdisplays.utils.MessageUtil.sendColoredMessage
import com.dreamdisplays.utils.MessageUtil.sendMessage
import com.dreamdisplays.utils.YouTubeUtil.sanitize
import com.dreamdisplays.utils.net.PacketUtils.sendDisplayInfo
import com.dreamdisplays.utils.net.PacketUtils.sendPremium
import com.dreamdisplays.utils.net.PacketUtils.sendReportEnabled
import com.github.zafarkhaja.semver.Version
import com.github.zafarkhaja.semver.Version.parse
import com.google.gson.Gson
import me.inotsleep.utils.logging.LoggingManager.*
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import org.jspecify.annotations.NullMarked
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.*

/**
 * Handles incoming channels from clients.
 */
@NullMarked
class PacketReceiver(private val plugin: Main) : PluginMessageListener {

    private val gson by lazy { Gson() }
    private val maxVersionBytes = 128

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        when (channel) {
            "dreamdisplays:sync" -> handleSyncPacket(player, message)
            "dreamdisplays:req_sync" -> handleRequestSync(player, message)
            "dreamdisplays:delete" -> handleDelete(player, message)
            "dreamdisplays:report" -> handleReport(player, message)
            "dreamdisplays:version" -> handleVersion(player, message)
            "dreamdisplays:display_enabled" -> handleDisplayEnabled(player, message)
            "dreamdisplays:set_video" -> handleSetVideo(player, message)
        }
    }

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
                processSyncPacket(syncData, player)
            }
        }.onFailure { error ->
            warn("Failed to decode sync packet", error)
        }
    }

    private fun handleRequestSync(player: Player, message: ByteArray) {
        readUUIDPacket(message)?.let { displayId ->
            sendSyncPacket(displayId, player)
        }
    }

    private fun handleDelete(player: Player, message: ByteArray) {
        readUUIDPacket(message)?.let { displayId ->
            val displayData = getDisplayData(displayId)
                ?: return@let sendMessage(player, "noDisplay")

            if (displayData.ownerId != player.uniqueId &&
                !player.hasPermission(config.permissions.delete)
            ) {
                sendMessage(player, "displayCommandMissingPermission")
                return@let
            }

            delete(displayId)
            sendMessage(player, "displayDeleted")
        }
    }

    private fun handleReport(player: Player, message: ByteArray) {
        readUUIDPacket(message)?.let { displayId ->
            report(displayId, player)
        }
    }

    private fun handleVersion(player: Player, message: ByteArray) {
        runCatching {
            val version = readVersionString(message)
            log("${player.name} joined with Dream Displays $version")

            initializePlayer(player, version)
            checkForUpdates(player, version)
        }.onFailure { error ->
            warn("Failed to process version packet", error)
        }
    }

    private fun handleDisplayEnabled(player: Player, message: ByteArray) {
        runCatching {
            DataInputStream(ByteArrayInputStream(message)).use { input ->
                val enabled = input.readBoolean()
                setDisplaysEnabled(player, enabled)
            }
        }.onFailure { error ->
            warn("Failed to decode display enabled packet", error)
        }
    }

    private fun initializePlayer(player: Player, versionString: String) {
        // Check for premium permission and send status
        sendPremium(
            player,
            player.hasPermission(config.permissions.premium)
        )

        // Send display enabled status
        sendReportEnabled(
            player,
            config.settings.webhookUrl.isNotEmpty()
        )

        // Send all displays in the player's world
        sendAllDisplays(player)

        // Store version
        val version = parseVersionOrNull(versionString)
        setVersion(player, version)
    }

    private fun checkForUpdates(player: Player, versionString: String) {
        val userVersion = parseVersionOrNull(versionString) ?: return

        // Check for mod update
        checkModUpdate(player, userVersion)

        // Check for plugin updates
        if (config.settings.updatesEnabled &&
            player.hasPermission(config.permissions.updates)
        ) {
            checkPluginUpdate(player)
        }
    }

    private fun checkModUpdate(player: Player, userVersion: Version) {
        val latestVersion = modVersion ?: return

        if (userVersion < latestVersion && !hasBeenNotifiedAboutModUpdate(player)) {
            sendModUpdateMessage(player, latestVersion)
            setModUpdateNotified(player, true)
        }
    }

    private fun checkPluginUpdate(player: Player) {
        val latestPluginVersion = Main.pluginLatestVersion ?: return

        if (hasBeenNotifiedAboutPluginUpdate(player)) return

        val currentVersionString = plugin.description.version
        if (currentVersionString.contains("-SNAPSHOT", ignoreCase = true)) {
            return
        }

        val currentVersion = runCatching { parse(currentVersionString) }.getOrNull() ?: return
        val latestVersion = runCatching { parse(latestPluginVersion) }.getOrNull() ?: return

        if (currentVersion < latestVersion) {
            sendPluginUpdateMessage(player, latestPluginVersion)
            setPluginUpdateNotified(player, true)
        }
    }

    private fun sendModUpdateMessage(player: Player, version: Version) {
        val message = when (val rawMessage = config.getMessageForPlayer(player, "newVersion")) {
            is String -> String.format(rawMessage, version.toString())
            else -> {
                val component = GsonComponentSerializer.gson()
                    .deserialize(gson.toJson(rawMessage))

                component.replaceText(
                    TextReplacementConfig.builder()
                        .matchLiteral("%s")
                        .replacement(version.toString())
                        .build()
                )
            }
        }

        sendColoredMessage(player, message)
    }

    private fun sendPluginUpdateMessage(player: Player, version: String) {
        val template = config.getMessageForPlayer(player, "newPluginVersion") as? String
            ?: "A new plugin version is available: %s"
        val message = String.format(template, version)
        sendColoredMessage(player, message)
    }

    private fun sendAllDisplays(player: Player) {
        val displays = getDisplays().filter { it.pos1.world == player.world }
        if (displays.isEmpty()) return

        val batchSize = 5
        val batches = displays.chunked(batchSize)
        batches.forEachIndexed { index, batch ->
            val delayTicks = (index * 2).toLong()
            if (delayTicks == 0L) {
                sendDisplayBatch(player, batch)
            } else {
                Scheduler.runLater(delayTicks) {
                    if (player.isOnline) sendDisplayBatch(player, batch)
                }
            }
        }
    }

    private fun sendDisplayBatch(player: Player, displays: List<com.dreamdisplays.datatypes.DisplayData>) {
        displays.forEach { display ->
            sendDisplayInfo(
                listOf(player),
                display.id,
                display.ownerId,
                display.box.min,
                display.width,
                display.height,
                display.url,
                display.lang,
                display.facing,
                display.isSync
            )
        }
    }

    private fun handleSetVideo(player: Player, message: ByteArray) {
        runCatching {
            DataInputStream(ByteArrayInputStream(message)).use { input ->
                val displayId = input.readUUID()
                val url = input.readString()
                val lang = input.readString()

                val displayData = getDisplayData(displayId)
                    ?: return@runCatching

                if (displayData.ownerId != player.uniqueId &&
                    !player.hasPermission(config.permissions.delete)
                ) return@runCatching

                displayData.url = url
                displayData.lang = lang
                displayData.isSync = false

                sendUpdate(displayData, getReceivers(displayData))
            }
        }.onFailure { error ->
            warn("Failed to decode set_video packet", error)
        }
    }

    private fun readUUIDPacket(message: ByteArray): UUID? {
        return runCatching {
            DataInputStream(ByteArrayInputStream(message)).use { input ->
                input.readUUID()
            }
        }.onFailure { error ->
            error("Failed to decode UUID packet", error)
        }.getOrNull()
    }

    private fun readVersionString(message: ByteArray): String {
        return DataInputStream(ByteArrayInputStream(message)).use { input ->
            val length = input.readVarInt()
            require(length in 1..maxVersionBytes) {
                "Invalid version packet length: $length"
            }
            require(length <= input.available()) {
                "Invalid version packet size: declared=$length available=${input.available()}"
            }
            val data = ByteArray(length)
            input.readFully(data)
            String(data, 0, length)
        }
    }

    private fun parseVersionOrNull(raw: String): Version? {
        val sanitized = sanitize(raw)?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { parse(sanitized) }.getOrNull()
    }

    // Extension functions for DataInputStream to read custom data types
    private fun DataInputStream.readUUID() = PacketUtils.run { readUUID() }
    private fun DataInputStream.readVarInt() = PacketUtils.run { readVarInt() }
    private fun DataInputStream.readVarLong() = PacketUtils.run { readVarLong() }
    private fun DataInputStream.readString(): String {
        val length = readVarInt()
        val data = ByteArray(length)
        readFully(data)
        return String(data, Charsets.UTF_8)
    }
}
