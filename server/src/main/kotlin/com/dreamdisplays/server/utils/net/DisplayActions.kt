package com.dreamdisplays.server.utils.net

import com.dreamdisplays.protocol.PlaybackAction
import com.dreamdisplays.protocol.PlaybackMode
import com.dreamdisplays.protocol.PlaybackPermissions
import com.dreamdisplays.protocol.WatchPartyAction
import com.dreamdisplays.server.Main
import com.dreamdisplays.server.datatypes.PaperDisplayData
import com.dreamdisplays.server.managers.DisplayManager
import com.dreamdisplays.server.managers.PlayerManager
import com.dreamdisplays.server.managers.StateManager
import com.dreamdisplays.server.meta.Scheduler
import com.dreamdisplays.server.playback.PlaybackContexts
import com.dreamdisplays.server.playback.TimelineManager
import com.dreamdisplays.server.playback.WatchPartyManager
import com.dreamdisplays.server.utils.MessageUtil
import com.dreamdisplays.server.utils.YouTubeUtil
import com.google.gson.Gson
import io.github.arsmotorin.ofrat.PaperOnly
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import org.semver4j.Semver
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Protocol-agnostic server-side actions triggered by client packets. Both the frozen-v1
 * [PacketReceiver] and the v2 [PaperV2Networking] dispatch here, so permission checks and
 * business logic exist exactly once.
 */
@PaperOnly @NullMarked object DisplayActions {
    private val logger = LoggerFactory.getLogger("DreamDisplays/DisplayActions")
    private val gson by lazy { Gson() }

    /** Handles a client-requested deletion, enforcing owner-or-permission check. */
    fun delete(player: Player, displayId: UUID) {
        val displayData = DisplayManager.getDisplayData(displayId)
            ?: return MessageUtil.sendMessage(player, "noDisplay")

        if (displayData.ownerId != player.uniqueId &&
            !player.hasPermission(Main.config.permissions.delete)
        ) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return
        }

        DisplayManager.delete(displayId)
        MessageUtil.sendMessage(player, "displayDeleted")
    }

    /** Applies a client-supplied URL / language to a display, broadcasting and resetting the timeline. */
    fun setVideo(player: Player, displayId: UUID, url: String, lang: String) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        if (!PlaybackPermissions.canSetVideo(context(displayData, player))) return

        val wasSync = displayData.isSync
        displayData.url = url
        displayData.lang = lang

        val receivers = DisplayManager.getReceivers(displayData)
        DisplayManager.sendUpdate(displayData, receivers)
        if (wasSync) StateManager.resetAndBroadcast(displayData.id, receivers) // frozen-v1 clock
        TimelineManager.onVideoChanged(displayData)
    }

    /** Updates the locked flag of a display owned by [player] and rebroadcasts. */
    fun setLocked(player: Player, displayId: UUID, locked: Boolean) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        if (!PlaybackPermissions.canToggleLock(context(displayData, player))) return

        displayData.isLocked = locked

        val receivers = DisplayManager.getReceivers(displayData)
        DisplayManager.sendUpdate(displayData, receivers)
    }

    /** Switches a display's persistent base mode (`LOCAL` / `SYNCED` / `BROADCAST`) and re-anchors its clock. */
    fun setMode(player: Player, displayId: UUID, mode: PlaybackMode, positionMs: Long) {
        if (!PlaybackMode.isBaseMode(mode)) return
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        if (!PlaybackPermissions.canSetMode(context(displayData, player))) return

        displayData.mode = mode
        DisplayManager.sendUpdate(displayData, DisplayManager.getReceivers(displayData))
        TimelineManager.onModeChanged(displayData, positionMs)
    }

    /** Applies a playback intent (play / pause / seek / restart) to a `SYNCED` display's server clock. */
    fun playbackCommand(player: Player, displayId: UUID, action: PlaybackAction, positionMs: Long) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        TimelineManager.onCommand(displayData, player.uniqueId, action, positionMs)
    }

    /** Starts a watch-party session with [player] as host. */
    fun watchPartyStart(player: Player, displayId: UUID, url: String, lang: String) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        WatchPartyManager.start(displayData, player.uniqueId, url, lang)
    }

    /** Routes a watch-party control (ready / host action) to the session manager. */
    fun watchPartyControl(player: Player, displayId: UUID, action: WatchPartyAction, positionMs: Long) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        WatchPartyManager.control(displayData, player.uniqueId, action, positionMs)
    }

    /** Replies to a client's catch-up request with the current timeline and any live session. */
    fun requestSync(player: Player, displayId: UUID) {
        val displayData = DisplayManager.getDisplayData(displayId) ?: return
        TimelineManager.sendCurrent(displayData, player.uniqueId)
        WatchPartyManager.sendCurrent(displayData, player.uniqueId)
    }

    /** Builds the permission context for [player] acting on [display]. */
    private fun context(display: PaperDisplayData, player: Player) =
        PlaybackContexts.of(display, player.uniqueId, player.hasPermission(Main.config.permissions.delete))

    /** Records the player's reported mod version and runs the mod / plugin update checks. */
    fun recordVersionAndCheckUpdates(player: Player, versionString: String) {
        logger.info("${player.name} joined with Dream Displays $versionString.")
        val version = parseVersionOrNull(versionString)
        PlayerManager.setVersion(player, version)

        if (version != null) checkModUpdate(player, version)
        if (Main.config.settings.updatesEnabled &&
            player.hasPermission(Main.config.permissions.updates)
        ) {
            checkPluginUpdate(player)
        }
    }

    /** Streams every display in [player]'s world to them in small staggered batches. */
    fun sendAllDisplays(player: Player) {
        val displays = DisplayManager.getDisplays()
            .filterIsInstance<PaperDisplayData>()
            .filter { it.pos1.world == player.world }
        if (displays.isEmpty()) return

        val batchSize = 5
        displays.chunked(batchSize).forEachIndexed { index, batch ->
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

    /** Sends a single batch of display-info packets to [player] (protocol chosen per player). */
    private fun sendDisplayBatch(player: Player, displays: List<PaperDisplayData>) {
        displays.forEach { display ->
            PacketUtil.sendDisplayInfo(
                listOf(player),
                display.id,
                display.ownerId,
                display.box.min,
                display.width,
                display.height,
                display.url,
                display.lang,
                display.facing,
                display.isSync,
                display.isLocked,
                display.mode,
                display.qualityCap,
            )
        }
    }

    /** Tells [player] about a newer mod version if they haven't been notified this session. */
    private fun checkModUpdate(player: Player, userVersion: Semver) {
        val latestVersion = Main.modVersion ?: return

        if (userVersion < latestVersion && !PlayerManager.hasBeenNotifiedAboutModUpdate(player)) {
            sendModUpdateMessage(player, latestVersion)
            PlayerManager.setModUpdateNotified(player, true)
        }
    }

    /** Tells privileged [player] about a newer plugin release; skipped for `-SNAPSHOT` builds. */
    @Suppress("DEPRECATION")
    private fun checkPluginUpdate(player: Player) {
        val latestPluginVersion = Main.pluginLatestVersion ?: return

        if (PlayerManager.hasBeenNotifiedAboutPluginUpdate(player)) return

        val currentVersionString = Main.getInstance().description.version
        if (currentVersionString.contains("-SNAPSHOT", ignoreCase = true) ||
            currentVersionString.contains("-DEV", ignoreCase = true)) {
            return
        }

        val currentVersion = Semver.coerce(currentVersionString) ?: return
        val latestVersion = Semver.coerce(latestPluginVersion) ?: return

        if (currentVersion < latestVersion) {
            sendPluginUpdateMessage(player, latestPluginVersion)
            PlayerManager.setPluginUpdateNotified(player, true)
        }
    }

    /** Sends the localized `newVersion` message to [player], handling both plain and JSON templates. */
    private fun sendModUpdateMessage(player: Player, version: Semver) {
        val message = when (val rawMessage = Main.config.getMessageForPlayer(player, "newVersion")) {
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
        MessageUtil.sendColoredMessage(player, message)
    }

    /** Sends the localized `newPluginVersion` message with the latest version interpolated in. */
    private fun sendPluginUpdateMessage(player: Player, version: String) {
        val template = Main.config.getMessageForPlayer(player, "newPluginVersion") as? String ?: return
        val message = String.format(template, version)
        MessageUtil.sendColoredMessage(player, message)
    }

    /** Sanitizes [raw] and coerces it into a [Semver], returning null if parsing fails. */
    private fun parseVersionOrNull(raw: String): Semver? {
        val sanitized = YouTubeUtil.sanitize(raw)?.takeIf { it.isNotEmpty() } ?: return null
        return Semver.coerce(sanitized)
    }
}
