package com.dreamdisplays.platform.server.utils.net

//? if >=1.21.11 {
import net.minecraft.server.players.NameAndId
//?}
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.shared.playback.PlaybackPermissions
import com.dreamdisplays.api.playback.model.WatchPartyAction
import com.dreamdisplays.shared.policy.MediaUrlPolicy
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.api.ServerApiHost
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.meta.VersionState
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.dreamdisplays.platform.server.utils.VersionUtil
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions.context
import kotlinx.coroutines.launch
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.semver4j.Semver
import org.slf4j.LoggerFactory

/**
 * Vanilla Minecraft API packet actions, shared between the receivers registered by
 * [VanillaServerPacketHandler] and the protocol-v2 dispatch in [V2Fabric] / [V2NeoForge]. All
 * business logic is shared by `Fabric` and `NeoForge`.
 */
object VanillaDisplayActions {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val setVideoThrottle = ActionThrottle()
    private const val SET_VIDEO_COOLDOWN_MS = 250L
    private val requestSyncThrottle = ActionThrottle()
    private const val REQUEST_SYNC_COOLDOWN_MS = 250L

    /** Tells a v1-only client that its Dream Displays is too old for this server. */
    fun notifyOutdatedClient(player: ServerPlayer) {
        logger.info("${player.name.string} joined with an outdated Dream Displays (protocol v1); asked to update.")
        ServerMessage.OUTDATED_CLIENT.sendTo(player)
    }

    /** Records the player's reported mod version and runs the mod / plugin update checks. */
    fun recordVersionAndCheckUpdates(player: ServerPlayer, version: String) {
        val parsedVersion = VersionUtil.parseOrNull(version)
        PlayerManager.setVersion(player.uuid, parsedVersion)

        val config = VanillaServerState.config
        val modLatest = VersionState.modLatestVersion
        if (modLatest != null && parsedVersion != null && parsedVersion < modLatest &&
            !PlayerManager.hasBeenNotifiedAboutModUpdate(player.uuid)
        ) {
            val msg = config.getMessageForPlayer(player, "newVersion")
            MessageUtil.sendColoredMessage(player, MessageUtil.formatMessage(msg, modLatest.toString()))
            PlayerManager.setModUpdateNotified(player.uuid, true)
        }

        if (config.settings.updatesEnabled &&
            VanillaPermissions.has(player, config.permissions.updates, VanillaPermissions.Fallback.OP) &&
            !PlayerManager.hasBeenNotifiedAboutPluginUpdate(player.uuid)
        ) {
            val latestPlugin = VersionState.pluginLatestVersion
            val currentVersion = VanillaServerState.serverVersion
            if (latestPlugin != null && currentVersion != null &&
                !currentVersion.contains("-SNAPSHOT", ignoreCase = true)
            ) {
                val current = Semver.coerce(currentVersion)
                val latest = Semver.coerce(latestPlugin)
                if (current != null && latest != null && current < latest) {
                    val msg = config.getMessageForPlayer(player, "newPluginVersion") as? String
                    if (msg != null) {
                        MessageUtil.sendColoredMessage(player, String.format(msg, latestPlugin))
                        PlayerManager.setPluginUpdateNotified(player.uuid, true)
                    }
                }
            }
        }
    }

    /** Streams every display in [player]'s world to them in small staggered batches. */
    fun sendAllDisplays(player: ServerPlayer, server: MinecraftServer) {
        val displays = DisplayManager.getDisplays()
            .filterIsInstance<VanillaDisplayData>()
            .filter { DisplayManager.isPlayerInRange(player, it) }

        val batchSize = 5
        displays.chunked(batchSize).forEachIndexed { index, batch ->
            if (index == 0) {
                batch.forEach { VanillaPacketUtil.sendDisplayInfo([player], it) }
            } else {
                val delayTicks = (index * 2).toLong()
                VanillaServerScheduler.runLater(server, delayTicks) {
                    if (server.playerList.players.contains(player)) {
                        batch.forEach { VanillaPacketUtil.sendDisplayInfo([player], it) }
                    }
                }
            }
        }
    }

    /** Handles a client-requested deletion, enforcing owner-or-permission check and physical proximity. */
    fun delete(player: ServerPlayer, server: MinecraftServer, displayId: java.util.UUID) {
        val displayData = DisplayManager.getDisplayData(displayId) as? VanillaDisplayData
            ?: return ServerMessage.NO_DISPLAY.sendTo(player)

        val perms = VanillaServerState.config.permissions
        val canDelete = if (displayData.ownerId == player.uuid) {
            VanillaPermissions.has(player, perms.delete, VanillaPermissions.Fallback.EVERYONE)
        } else {
            VanillaPermissions.has(player, perms.deleteOthers, VanillaPermissions.Fallback.OP)
        }
        if (!canDelete) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return
        }
        if (!DisplayManager.isPlayerInRange(player, displayData)) {
            return ServerMessage.NO_DISPLAY.sendTo(player)
        }

        ServerApiHost.current?.askDelete(displayData, player.uuid)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return
        }

        DisplayManager.delete(displayData)
        ServerMessage.DISPLAY_DELETED.sendTo(player)
    }

    /** Applies a client-supplied URL / language to a display, broadcasting and resetting the timeline. */
    fun setVideo(player: ServerPlayer, server: MinecraftServer, displayId: java.util.UUID, url: String, lang: String) {
        val displayData = DisplayManager.getDisplayData(displayId) as? VanillaDisplayData ?: return
        if (!PlaybackPermissions.canSetVideo(context(displayData, player))) return
        if (!MediaUrlPolicy.isAllowed(url)) return
        CustomMediaGate.refusalKey(
            url,
            VanillaServerState.config.settings.customMediaPolicy,
            VanillaPermissions.has(
                player,
                VanillaServerState.config.permissions.custom,
                VanillaPermissions.Fallback.EVERYONE,
            ),
            player.uuid,
        )?.let { return MessageUtil.sendMessage(player, it) }
        // Checked before the throttle below: an attacker who is never nearby must not be able to
        // burn the per-display cooldown window against the display's real, present owner.
        if (!DisplayManager.isPlayerInRange(player, displayData)) return
        if (!setVideoThrottle.tryAcquire(displayId, SET_VIDEO_COOLDOWN_MS)) return

        val sanitizedLang = MediaUrlPolicy.sanitizeLang(lang)
        ServerApiHost.current?.askSetUrl(displayData, url, sanitizedLang, player.uuid)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return
        }

        displayData.url = url
        displayData.lang = sanitizedLang
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(displayData) }

        val receivers = DisplayManager.getReceivers(displayData, server)
        VanillaPacketUtil.sendDisplayInfo(receivers, displayData)
        TimelineManager.onVideoChanged(displayData)
        ServerApiHost.current?.urlChanged(displayData)
    }

    /** Sets who may use a display owned by [player] and rebroadcasts. */
    fun setAccess(player: ServerPlayer, server: MinecraftServer, displayId: java.util.UUID, access: DisplayAccess) {
        val displayData = DisplayManager.getDisplayData(displayId) as? VanillaDisplayData
            ?: return ServerMessage.NO_DISPLAY.sendTo(player)
        if (!VanillaPermissions.has(player, VanillaServerState.config.permissions.lock, VanillaPermissions.Fallback.EVERYONE)) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return
        }
        if (!PlaybackPermissions.canToggleLock(lockContext(displayData, player))) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return
        }
        if (!DisplayManager.isPlayerInRange(player, displayData)) return

        displayData.access = access
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(displayData) }

        val receivers = DisplayManager.getReceivers(displayData, server)
        VanillaPacketUtil.sendDisplayInfo(receivers, displayData)
    }

    /** Switches a display's persistent base mode (`LOCAL` / `SYNCED` / `BROADCAST`) and re-anchors its clock. */
    fun setMode(
        player: ServerPlayer,
        server: MinecraftServer,
        displayId: java.util.UUID,
        mode: PlaybackMode,
        positionMs: Long
    ) {
        if (!PlaybackMode.isBaseMode(mode)) return
        val displayData = DisplayManager.getDisplayData(displayId) as? VanillaDisplayData ?: return
        if (!PlaybackPermissions.canSetMode(context(displayData, player))) return

        if (!canAccessMode(player, mode)) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return
        }
        if (!DisplayManager.isPlayerInRange(player, displayData)) return

        displayData.mode = mode
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(displayData) }
        VanillaPacketUtil.sendDisplayInfo(DisplayManager.getReceivers(displayData, server), displayData)
        TimelineManager.onModeChanged(displayData, positionMs)
    }

    /** Applies a playback intent (play / pause / seek / restart) to a `SYNCED` display's server clock. */
    fun playbackCommand(player: ServerPlayer, displayId: java.util.UUID, action: PlaybackAction, positionMs: Long) {
        val displayData = DisplayManager.getDisplayData(displayId) as? VanillaDisplayData ?: return
        TimelineManager.onCommand(displayData, player.uuid, action, positionMs)
    }

    /** Starts a watch-party session with [player] as host. */
    fun watchPartyStart(player: ServerPlayer, displayId: java.util.UUID, url: String, lang: String) {
        val displayData = DisplayManager.getDisplayData(displayId) as? VanillaDisplayData ?: return
        val perms = VanillaServerState.config.permissions
        if (!VanillaPermissions.has(player, perms.watchparty, VanillaPermissions.Fallback.EVERYONE)) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return
        }
        if (!MediaUrlPolicy.isAllowed(url)) return
        CustomMediaGate.refusalKey(
            url,
            VanillaServerState.config.settings.customMediaPolicy,
            VanillaPermissions.has(
                player,
                VanillaServerState.config.permissions.custom,
                VanillaPermissions.Fallback.EVERYONE,
            ),
            player.uuid,
        )?.let { return MessageUtil.sendMessage(player, it) }
        if (!DisplayManager.isPlayerInRange(player, displayData)) return
        WatchPartyManager.start(displayData, player.uuid, url, MediaUrlPolicy.sanitizeLang(lang))
    }

    /** Routes a watch-party control (ready / host action) to the session manager. */
    fun watchPartyControl(player: ServerPlayer, displayId: java.util.UUID, action: WatchPartyAction, positionMs: Long) {
        val displayData = DisplayManager.getDisplayData(displayId) as? VanillaDisplayData ?: return
        WatchPartyManager.control(displayData, player.uuid, action, positionMs)
    }

    /** Replies to a client's catch-up request with the current timeline and any live session. */
    fun requestSync(player: ServerPlayer, displayId: java.util.UUID) {
        if (!requestSyncThrottle.tryAcquire(displayId to player.uuid, REQUEST_SYNC_COOLDOWN_MS)) return
        if (FullscreenBroadcastManager.sendCurrentTo(displayId, player.uuid)) return
        val displayData = DisplayManager.getDisplayData(displayId) ?: return
        TimelineManager.sendCurrent(displayData, player.uuid)
        WatchPartyManager.sendCurrent(displayData, player.uuid)
    }

    /** Applies a client-reported media duration to the display's server timeline (SYNCED/BROADCAST only). */
    fun reportDuration(player: ServerPlayer, displayId: java.util.UUID, durationMs: Long) {
        val displayData = DisplayManager.getDisplayData(displayId) ?: return
        TimelineManager.onDurationReported(displayData, player.uuid, durationMs)
    }

    private fun context(display: VanillaDisplayData, player: ServerPlayer) =
        PlaybackContexts.of(display, player.uuid, isAdmin(player))

    private fun lockContext(display: VanillaDisplayData, player: ServerPlayer) = context(display, player)

    private fun canAccessMode(player: ServerPlayer, mode: PlaybackMode): Boolean {
        val perms = VanillaServerState.config.permissions
        val node = when (mode) {
            PlaybackMode.LOCAL -> perms.local
            PlaybackMode.SYNCED -> perms.synced
            PlaybackMode.BROADCAST -> perms.broadcast
            else -> return true
        }
        return VanillaPermissions.has(player, node, VanillaPermissions.Fallback.EVERYONE)
    }

    /** True if [player] counts as a display admin (the `delete` node, or op level 2 without `LuckPerms`). */
    fun isAdmin(player: ServerPlayer): Boolean =
        VanillaPermissions.has(player, VanillaServerState.config.permissions.deleteOthers, VanillaPermissions.Fallback.OP)

    /** True if [player] holds the premium node (op level 2 without LuckPerms, matching legacy behavior). */
    fun isPremium(player: ServerPlayer): Boolean =
        VanillaPermissions.has(player, VanillaServerState.config.permissions.premium, VanillaPermissions.Fallback.OP)

    /** [ServerHello.maxDisplays] for [player]: `-1` (unlimited) when they hold `create_bypass` or no cap is configured. */
    fun maxDisplaysFor(player: ServerPlayer): Int {
        val cap = VanillaServerState.config.settings.maxDisplaysPerPlayer
        val hasBypass = VanillaPermissions.has(
            player, VanillaServerState.config.permissions.createBypass, VanillaPermissions.Fallback.OP,
        )
        return if (hasBypass || cap <= 0) -1 else cap
    }

    /** Checks if [player] has operator level 2 permissions, the no-LuckPerms fallback for privileged actions. */
    fun isOpLevel2(player: ServerPlayer): Boolean {
        val server =
            //? if >=1.21.11 {
            player.level().server
        //?} else
        /*
        //? if >=1.20 {
        player.serverLevel().server
        //?}
        //? if <1.20 {
        player.getLevel().server
        //?}
        */
        return server.playerList.isOp(
            //? if >=1.21.11 {
            NameAndId(player.gameProfile)
            //?} else
            /*player.gameProfile*/
        )
    }
}
