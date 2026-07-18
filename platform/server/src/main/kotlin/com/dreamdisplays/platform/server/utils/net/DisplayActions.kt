package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.api.playback.PlaybackAction
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.playback.PlaybackPermissions
import com.dreamdisplays.api.playback.WatchPartyAction
import com.dreamdisplays.api.security.MediaUrlPolicy
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.meta.VersionState
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.VersionUtil
import com.dreamdisplays.platform.server.utils.net.DisplayActions.context
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import org.semver4j.Semver
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Protocol-agnostic server-side actions triggered by client packets. Both the frozen-v1
 * [PacketReceiver] and the v2 [PaperV2Networking] dispatch here, so permission checks and
 * business logic exist exactly once.
 */
@PaperOnly
@NullMarked
object DisplayActions {
    private val logger = LoggerFactory.getLogger("DreamDisplays/DisplayActions")

    /**
     * Bounds how often one display's video can be changed — each call persists to disk, broadcasts to
     * every viewer, and makes every viewer's client re-resolve the new URL, so unlike the cheap
     * per-packet sync state this genuinely amplifies.
     */
    private val setVideoThrottle = ActionThrottle()
    private const val SET_VIDEO_COOLDOWN_MS = 250L

    /** Bounds how often one player may request a catch-up snapshot for one display. */
    private val requestSyncThrottle = ActionThrottle()
    private const val REQUEST_SYNC_COOLDOWN_MS = 250L

    /** Handles a client-requested deletion, enforcing owner-or-permission check. */
    fun delete(player: Player, displayId: UUID) {
        val displayData = DisplayManager.getDisplayData(displayId)
            ?: return MessageUtil.sendMessage(player, "noDisplay")

        val isOwner = displayData.ownerId == player.uniqueId
        val canDelete = isOwner || player.hasPermission(PaperServer.config.permissions.deleteOthers)
        if (!canDelete) {
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
        if (!MediaUrlPolicy.isAllowed(url)) return
        if (!setVideoThrottle.tryAcquire(displayId, SET_VIDEO_COOLDOWN_MS)) return

        val wasSync = displayData.isSync
        displayData.url = url
        displayData.lang = MediaUrlPolicy.sanitizeLang(lang)

        runAsync { PaperServer.getInstance().storage.saveDisplay(displayData) }
        DisplayManager.broadcastUpdate(displayData)
        if (wasSync) StateManager.resetAndBroadcast(displayData) // Frozen-v1 clock
        TimelineManager.onVideoChanged(displayData)
    }

    /** Updates the locked flag of a display owned by [player] and rebroadcasts. */
    fun setLocked(player: Player, displayId: UUID, locked: Boolean) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        if (!PlaybackPermissions.canToggleLock(lockContext(displayData, player))) return

        displayData.isLocked = locked

        runAsync { PaperServer.getInstance().storage.saveDisplay(displayData) }
        DisplayManager.broadcastUpdate(displayData)
    }

    /** Switches a display's persistent base mode (`LOCAL` / `SYNCED` / `BROADCAST`) and re-anchors its clock. */
    fun setMode(player: Player, displayId: UUID, mode: PlaybackMode, positionMs: Long) {
        if (!PlaybackMode.isBaseMode(mode)) return
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        if (!PlaybackPermissions.canSetMode(context(displayData, player))) return

        // Check mode-specific permissions
        if (!canAccessMode(player, mode)) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return
        }

        displayData.mode = mode
        runAsync { PaperServer.getInstance().storage.saveDisplay(displayData) }
        DisplayManager.broadcastUpdate(displayData)
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
        if (!player.hasPermission(PaperServer.config.permissions.watchparty)) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return
        }
        if (!MediaUrlPolicy.isAllowed(url)) {
            logger.warn("Rejected unsafe watch-party URL from ${player.name}: ${url.take(120)}")
            return
        }
        WatchPartyManager.start(displayData, player.uniqueId, url, MediaUrlPolicy.sanitizeLang(lang))
    }

    /** Routes a watch-party control (ready / host action) to the session manager. */
    fun watchPartyControl(player: Player, displayId: UUID, action: WatchPartyAction, positionMs: Long) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        WatchPartyManager.control(displayData, player.uniqueId, action, positionMs)
    }

    /** Replies to a client's catch-up request with the current timeline and any live session. */
    fun requestSync(player: Player, displayId: UUID) {
        val displayData = DisplayManager.getDisplayData(displayId) ?: return
        if (!requestSyncThrottle.tryAcquire(displayId to player.uniqueId, REQUEST_SYNC_COOLDOWN_MS)) return
        TimelineManager.sendCurrent(displayData, player.uniqueId)
        WatchPartyManager.sendCurrent(displayData, player.uniqueId)
    }

    /** Builds the permission context for [player] acting on [display]. */
    private fun context(display: PaperDisplayData, player: Player) =
        PlaybackContexts.of(display, player.uniqueId, player.hasPermission(PaperServer.config.permissions.delete))

    /** Like [context] but elevates [player] to admin if they hold the [lock][PermissionsSection.lock] permission. */
    private fun lockContext(display: PaperDisplayData, player: Player) =
        PlaybackContexts.of(
            display, player.uniqueId,
            player.hasPermission(PaperServer.config.permissions.delete) || player.hasPermission(PaperServer.config.permissions.lock)
        )

    /** Checks if [player] has permission to access the specified [mode]. */
    private fun canAccessMode(player: Player, mode: PlaybackMode): Boolean {
        val permission = when (mode) {
            PlaybackMode.LOCAL -> PaperServer.config.permissions.local
            PlaybackMode.SYNCED -> PaperServer.config.permissions.synced
            PlaybackMode.BROADCAST -> PaperServer.config.permissions.broadcast
            else -> return true
        }
        return player.hasPermission(permission)
    }

    /** Records the player's reported mod version and runs the mod / plugin update checks. */
    fun recordVersionAndCheckUpdates(player: Player, versionString: String) {
        logger.info("${player.name} joined with Dream Displays $versionString.")
        val version = VersionUtil.parseOrNull(versionString)
        PlayerManager.setVersion(player, version)

        if (version != null) checkModUpdate(player, version)
        if (PaperServer.config.settings.updatesEnabled &&
            player.hasPermission(PaperServer.config.permissions.updates)
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
                Scheduler.runPlayerLater(player, delayTicks) {
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
                display.rotation,
            )
        }
    }

    /** Tells [player] about a newer mod version if they haven't been notified this session. */
    private fun checkModUpdate(player: Player, userVersion: Semver) {
        val latestVersion = VersionState.modLatestVersion ?: return

        if (userVersion < latestVersion && !PlayerManager.hasBeenNotifiedAboutModUpdate(player)) {
            sendModUpdateMessage(player, latestVersion)
            PlayerManager.setModUpdateNotified(player, true)
        }
    }

    /** Tells privileged [player] about a newer plugin release; skipped for `-SNAPSHOT` builds. */
    @Suppress("DEPRECATION")
    private fun checkPluginUpdate(player: Player) {
        val latestPluginVersion = VersionState.pluginLatestVersion ?: return

        if (PlayerManager.hasBeenNotifiedAboutPluginUpdate(player)) return

        val currentVersionString = PaperServer.getInstance().description.version
        if (currentVersionString.contains("-SNAPSHOT", ignoreCase = true) ||
            currentVersionString.contains("-DEV", ignoreCase = true)
        ) {
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
        val message = when (val rawMessage = PaperServer.config.getMessageForPlayer(player, "newVersion")) {
            is String -> String.format(rawMessage, version.toString())
            else -> {
                val component = MessageUtil.deserializeJsonComponent(rawMessage)

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
        val template = PaperServer.config.getMessageForPlayer(player, "newPluginVersion") as? String ?: return
        val message = String.format(template, version)
        MessageUtil.sendColoredMessage(player, message)
    }
}
