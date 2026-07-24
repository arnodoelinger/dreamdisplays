package com.dreamdisplays.platform.server.utils.net

//? if >=1.21.11 {
import net.minecraft.server.players.NameAndId
//?}
import com.dreamdisplays.api.playback.PlaybackAction
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.playback.PlaybackPermissions
import com.dreamdisplays.api.playback.WatchPartyAction
import com.dreamdisplays.api.security.MediaUrlPolicy
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.meta.VersionState
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.dreamdisplays.platform.server.utils.VersionUtil
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions.context
import kotlinx.coroutines.launch
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.semver4j.Semver

/**
 * Vanilla Minecraft API packet actions, shared between the frozen v1 receivers registered by
 * [VanillaServerPacketHandler] and the protocol-v2 dispatch in [V2Fabric] / [V2NeoForge]. All
 * business logic is shared by `Fabric` and `NeoForge`.
 */
object VanillaDisplayActions {
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
        val playerWorldKey = RegionUtil.getPlayerLevelKey(player)
        val displays = DisplayManager.getDisplays()
            .filterIsInstance<VanillaDisplayData>()
            .filter { it.worldKey == playerWorldKey }

        val batchSize = 5
        displays.chunked(batchSize).forEachIndexed { index, batch ->
            if (index == 0) {
                batch.forEach { VanillaPacketUtil.sendDisplayInfo(listOf(player), it) }
            } else {
                val delayTicks = (index * 2).toLong()
                VanillaServerScheduler.runLater(server, delayTicks) {
                    if (server.playerList.players.contains(player)) {
                        batch.forEach { VanillaPacketUtil.sendDisplayInfo(listOf(player), it) }
                    }
                }
            }
        }
    }

    /** Handles a client-requested deletion, enforcing owner-or-permission check. */
    fun delete(player: ServerPlayer, server: MinecraftServer, displayId: java.util.UUID) {
        val displayData = DisplayManager.getDisplayData(displayId) as? VanillaDisplayData
            ?: return MessageUtil.sendMessage(player, "noDisplay")

        val perms = VanillaServerState.config.permissions
        if (displayData.ownerId != player.uuid &&
            !VanillaPermissions.has(player, perms.deleteOthers, VanillaPermissions.Fallback.OP)
        ) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return
        }

        DisplayManager.delete(displayData)
        MessageUtil.sendMessage(player, "displayDeleted")
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
        )?.let { return MessageUtil.sendMessage(player, it) }
        if (!setVideoThrottle.tryAcquire(displayId, SET_VIDEO_COOLDOWN_MS)) return

        val wasSync = displayData.isSync
        displayData.url = url
        displayData.lang = MediaUrlPolicy.sanitizeLang(lang)
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(displayData) }

        val receivers = DisplayManager.getReceivers(displayData, server)
        VanillaPacketUtil.sendDisplayInfo(receivers, displayData)
        if (wasSync) StateManager.resetAndBroadcast(displayId, receivers) // Frozen-v1 clock
        TimelineManager.onVideoChanged(displayData)
    }

    /** Updates the locked flag of a display owned by [player] and rebroadcasts. */
    fun setLocked(player: ServerPlayer, server: MinecraftServer, displayId: java.util.UUID, locked: Boolean) {
        val displayData = DisplayManager.getDisplayData(displayId) as? VanillaDisplayData
            ?: return MessageUtil.sendMessage(player, "noDisplay")
        if (!PlaybackPermissions.canToggleLock(lockContext(displayData, player))) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return
        }

        displayData.isLocked = locked
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
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return
        }

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
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return
        }
        if (!MediaUrlPolicy.isAllowed(url)) return
        WatchPartyManager.start(displayData, player.uuid, url, MediaUrlPolicy.sanitizeLang(lang))
    }

    /** Routes a watch-party control (ready / host action) to the session manager. */
    fun watchPartyControl(player: ServerPlayer, displayId: java.util.UUID, action: WatchPartyAction, positionMs: Long) {
        val displayData = DisplayManager.getDisplayData(displayId) as? VanillaDisplayData ?: return
        WatchPartyManager.control(displayData, player.uuid, action, positionMs)
    }

    /** Replies to a client's catch-up request with the current timeline and any live session. */
    fun requestSync(player: ServerPlayer, displayId: java.util.UUID) {
        val displayData = DisplayManager.getDisplayData(displayId) ?: return
        if (!requestSyncThrottle.tryAcquire(displayId to player.uuid, REQUEST_SYNC_COOLDOWN_MS)) return
        TimelineManager.sendCurrent(displayData, player.uuid)
        WatchPartyManager.sendCurrent(displayData, player.uuid)
    }

    /** Builds the permission context for [player] acting on [display]. */
    private fun context(display: VanillaDisplayData, player: ServerPlayer) =
        PlaybackContexts.of(display, player.uuid, isAdmin(player))

    /** Like [context] but elevates [player] to admin if they hold the lock permission. */
    private fun lockContext(display: VanillaDisplayData, player: ServerPlayer) =
        PlaybackContexts.of(
            display, player.uuid,
            isAdmin(player) ||
                    VanillaPermissions.has(
                        player,
                        VanillaServerState.config.permissions.lock,
                        VanillaPermissions.Fallback.OP
                    ),
        )

    /** Checks if [player] has permission to access the specified [mode]. */
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

    /** True if [player] counts as a display admin (the `delete` node, or op level 2 without LuckPerms). */
    fun isAdmin(player: ServerPlayer): Boolean =
        VanillaPermissions.has(player, VanillaServerState.config.permissions.delete, VanillaPermissions.Fallback.OP)

    /** True if [player] holds the premium node (op level 2 without LuckPerms, matching legacy behavior). */
    fun isPremium(player: ServerPlayer): Boolean =
        VanillaPermissions.has(player, VanillaServerState.config.permissions.premium, VanillaPermissions.Fallback.OP)

    /** Checks if [player] has operator level 2 permissions, the no-LuckPerms fallback for privileged actions. */
    fun isOpLevel2(player: ServerPlayer): Boolean {
        val server =
            //? if >=1.21.11 {
            player.level().server
        //?} else
        /*player.serverLevel().server*/
        return server.playerList.isOp(
            //? if >=1.21.11 {
            NameAndId(player.gameProfile)
            //?} else
            /*player.gameProfile*/
        )
    }
}
