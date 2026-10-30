package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.shared.playback.PlaybackPermissions
import com.dreamdisplays.api.playback.model.WatchPartyAction
import com.dreamdisplays.shared.policy.MediaUrlPolicy
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.api.ServerApiHost
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.meta.VersionState
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.platform.server.utils.VersionUtil
import com.dreamdisplays.platform.server.utils.WorldGuardRegions
import com.dreamdisplays.platform.server.utils.net.DisplayActions.context
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import org.semver4j.Semver
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Server-side actions triggered by client packets. The v2 [PaperV2Networking] dispatches here,
 * so permission checks and business logic exist exactly once.
 */
@PaperOnly
@NullMarked
object DisplayActions {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val setVideoThrottle = ActionThrottle()
    private const val SET_VIDEO_COOLDOWN_MS = 250L

    private fun String.sanitizedForLog(maxLength: Int = 200): String = take(maxLength).filter { !it.isISOControl() }

    private val requestSyncThrottle = ActionThrottle()
    private const val REQUEST_SYNC_COOLDOWN_MS = 250L

    /** Handles a client-requested deletion, enforcing owner-or-permission check and physical proximity. */
    fun delete(player: Player, displayId: UUID) {
        val displayData = DisplayManager.getDisplayData(displayId)
            ?: return ServerMessage.NO_DISPLAY.sendTo(player)

        val isOwner = displayData.ownerId == player.uniqueId
        val canDelete = (isOwner && player.hasPermission(PaperServer.config.permissions.delete)) ||
                player.hasPermission(PaperServer.config.permissions.deleteOthers)
        if (!canDelete) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return
        }
        if (displayData !is PaperDisplayData || !DisplayManager.isPlayerInRange(player, displayData)) {
            return ServerMessage.NO_DISPLAY.sendTo(player)
        }

        ServerApiHost.current?.askDelete(displayData, player.uniqueId)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return
        }

        DisplayManager.delete(displayId)
        ServerMessage.DISPLAY_DELETED.sendTo(player)
    }

    /** Applies a client-supplied URL / language to a display, broadcasting and resetting the timeline. */
    fun setVideo(player: Player, displayId: UUID, url: String, lang: String) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        if (!PlaybackPermissions.canSetVideo(context(displayData, player))) return
        if (!MediaUrlPolicy.isAllowed(url)) return
        // Custom links go through the server's own policy on top of the URL-shape check: a player
        // is told why the link was refused rather than watching the display silently not change.
        CustomMediaGate.refusalKey(
            url,
            PaperServer.config.settings.customMediaPolicy,
            player.hasPermission(PaperServer.config.permissions.custom),
            player.uniqueId,
        )?.let { return MessageUtil.sendMessage(player, it) }
        // Checked before the throttle below: an attacker who is never nearby must not be able to
        // burn the per-display cooldown window against the display's real, present owner.
        if (!DisplayManager.isPlayerInRange(player, displayData)) return
        if (!setVideoThrottle.tryAcquire(displayId, SET_VIDEO_COOLDOWN_MS)) return

        val sanitizedLang = MediaUrlPolicy.sanitizeLang(lang)
        ServerApiHost.current?.askSetUrl(displayData, url, sanitizedLang, player.uniqueId)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return
        }

        displayData.url = url
        displayData.lang = sanitizedLang

        runAsync { PaperServer.getInstance().storage.saveDisplay(displayData) }
        DisplayManager.broadcastUpdate(displayData)
        TimelineManager.onVideoChanged(displayData)
        ServerApiHost.current?.urlChanged(displayData)
    }

    /** Sets who may use a display owned by [player] and rebroadcasts. */
    fun setAccess(player: Player, displayId: UUID, access: DisplayAccess) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        if (!player.hasPermission(PaperServer.config.permissions.lock)) return
        if (!PlaybackPermissions.canToggleLock(lockContext(displayData, player))) return
        if (!DisplayManager.isPlayerInRange(player, displayData)) return

        displayData.access = access

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
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return
        }
        if (!DisplayManager.isPlayerInRange(player, displayData)) return

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
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return
        }
        if (!MediaUrlPolicy.isAllowed(url)) {
            logger.warn("Rejected unsafe watch-party URL from ${player.name}: ${url.sanitizedForLog(120)}")
            return
        }
        CustomMediaGate.refusalKey(
            url,
            PaperServer.config.settings.customMediaPolicy,
            player.hasPermission(PaperServer.config.permissions.custom),
            player.uniqueId,
        )?.let { return MessageUtil.sendMessage(player, it) }
        if (!DisplayManager.isPlayerInRange(player, displayData)) return
        WatchPartyManager.start(displayData, player.uniqueId, url, MediaUrlPolicy.sanitizeLang(lang))
    }

    /** Routes a watch-party control (ready / host action) to the session manager. */
    fun watchPartyControl(player: Player, displayId: UUID, action: WatchPartyAction, positionMs: Long) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        WatchPartyManager.control(displayData, player.uniqueId, action, positionMs)
    }

    /** Replies to a client's catch-up request with the current timeline and any live session. */
    fun requestSync(player: Player, displayId: UUID) {
        if (!requestSyncThrottle.tryAcquire(displayId to player.uniqueId, REQUEST_SYNC_COOLDOWN_MS)) return
        if (FullscreenBroadcastManager.sendCurrentTo(displayId, player.uniqueId)) return
        val displayData = DisplayManager.getDisplayData(displayId) ?: return
        TimelineManager.sendCurrent(displayData, player.uniqueId)
        WatchPartyManager.sendCurrent(displayData, player.uniqueId)
    }

    /** Applies a client-reported media duration to the display's server timeline (SYNCED / BROADCAST only). */
    fun reportDuration(player: Player, displayId: UUID, durationMs: Long) {
        val displayData = DisplayManager.getDisplayData(displayId) ?: return
        TimelineManager.onDurationReported(displayData, player.uniqueId, durationMs)
    }

    private fun context(display: PaperDisplayData, player: Player) =
        PlaybackContexts.of(display, player.uniqueId, isAdmin(player)) {
            WorldGuardRegions.isRegionMember(player, display.pos1)
        }

    private fun lockContext(display: PaperDisplayData, player: Player) = context(display, player)

    private fun isAdmin(player: Player) = player.hasPermission(PaperServer.config.permissions.deleteOthers)

    private fun canAccessMode(player: Player, mode: PlaybackMode): Boolean {
        val permission = when (mode) {
            PlaybackMode.LOCAL -> PaperServer.config.permissions.local
            PlaybackMode.SYNCED -> PaperServer.config.permissions.synced
            PlaybackMode.BROADCAST -> PaperServer.config.permissions.broadcast
            else -> return true
        }
        return player.hasPermission(permission)
    }

    /** Tells a v1-only client that its Dream Displays is too old for this server. */
    fun notifyOutdatedClient(player: Player) {
        logger.info("${player.name} joined with an outdated Dream Displays (protocol v1); asked to update.")
        ServerMessage.OUTDATED_CLIENT.sendTo(player)
    }

    /** Records the player's reported mod version and runs the mod / plugin update checks. */
    fun recordVersionAndCheckUpdates(player: Player, versionString: String) {
        logger.info("${player.name} joined with Dream Displays ${versionString.sanitizedForLog()}.")
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
            .filter { DisplayManager.isPlayerInRange(player, it) }
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

    private fun sendDisplayBatch(player: Player, displays: List<PaperDisplayData>) {
        displays.forEach { display ->
            PacketUtil.sendDisplayInfo(
                [player],
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
                display.access,
                display.mode,
                display.qualityCap,
                display.rotation,
                inRegion = WorldGuardRegions.isProtectedTerritory(display.pos1),
                isRegionMember = { WorldGuardRegions.isRegionMember(it, display.pos1) },
                depth = display.depth, conforming = display.conforming,
                customData = display.customData.toMap(),
            )
        }
    }

    private fun checkModUpdate(player: Player, userVersion: Semver) {
        val latestVersion = VersionState.modLatestVersion ?: return

        if (userVersion < latestVersion && !PlayerManager.hasBeenNotifiedAboutModUpdate(player)) {
            sendModUpdateMessage(player, latestVersion)
            PlayerManager.setModUpdateNotified(player, true)
        }
    }

    private fun checkPluginUpdate(player: Player) {
        val latestPluginVersion = VersionState.pluginLatestVersion ?: return

        if (PlayerManager.hasBeenNotifiedAboutPluginUpdate(player)) return

        @Suppress("DEPRECATION")
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

    private fun sendPluginUpdateMessage(player: Player, version: String) {
        val template = PaperServer.config.getMessageForPlayer(player, "newPluginVersion") as? String ?: return
        val message = String.format(template, version)
        MessageUtil.sendColoredMessage(player, message)
    }
}
