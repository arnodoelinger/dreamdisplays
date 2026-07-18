package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.api.playback.FullscreenMode
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.FullscreenRadiusTarget
import com.dreamdisplays.platform.server.playback.FullscreenSessionInfo
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*

/** Outcome of a `/display fullscreen start` attempt, for the platform trees to turn into a reply message. */
sealed class FullscreenStartResult {
    data class Started(val sessionId: String, val reach: Int) : FullscreenStartResult()
    data object NoTargets : FullscreenStartResult()
    data object AlreadyRunning : FullscreenStartResult()
    data object ForcedDisallowed : FullscreenStartResult()
}

/**
 * Shared `/display fullscreen` logic, common to both the `Paper` and `Fabric` / `NeoForge` command trees
 * (see `CommandRegistrar.kt` / `VanillaCommandTree.kt` for the thin per-platform adapters, which
 * build every flag - `target`, `radius`, `mode`, `forced`, `transient`, `volume` — as a proper
 * Brigadier node with tab-complete, combinable in any order). Targets and radius are independent
 * and combinable - a player is delivered the broadcast if they match either one.
 */
object FullscreenCommand {
    /** Starts a session on [display]. */
    fun start(
        display: DisplayData,
        virtual: Boolean,
        ownerId: UUID,
        mode: FullscreenMode?,
        forced: Boolean,
        transientSession: Boolean,
        volume: Float?,
        loop: Boolean,
        quality: String?,
        targetNamesRaw: String?,
        radiusBlocks: Double?,
        radiusX: Double?,
        radiusY: Double?,
        radiusZ: Double?,
        defaultMode: FullscreenMode,
        allowForced: Boolean,
        senderWorld: String,
        senderX: Double,
        senderY: Double,
        senderZ: Double,
        resolveTarget: (String) -> Set<UUID>,
    ): FullscreenStartResult {
        if (forced && !allowForced) return FullscreenStartResult.ForcedDisallowed

        val namedTargets = targetNamesRaw
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.flatMap(resolveTarget)?.toSet()?.takeIf { it.isNotEmpty() }
        val radius = radiusBlocks?.let { blocks ->
            FullscreenRadiusTarget(senderWorld, radiusX ?: senderX, radiusY ?: senderY, radiusZ ?: senderZ, blocks)
        }
        if (namedTargets == null && radius == null) return FullscreenStartResult.NoTargets

        val sessionId = FullscreenBroadcastManager.start(
            sessionId = UUID.randomUUID().toString().take(8),
            display = display,
            virtual = virtual,
            transientSession = transientSession,
            ownerId = ownerId,
            mode = mode ?: defaultMode,
            forced = forced,
            volume = volume ?: -1f,
            loop = loop,
            quality = quality ?: "",
            title = "",
            namedTargets = namedTargets,
            radius = radius,
        ) ?: return FullscreenStartResult.AlreadyRunning

        val reach = FullscreenBroadcastManager.list().firstOrNull { it.sessionId == sessionId }?.reach ?: 0
        return FullscreenStartResult.Started(sessionId, reach)
    }

    /** Stops a session by its own id, by the id of the real display it's hosted on, or every session for `all`. Returns how many were stopped. */
    fun stop(idOrAll: String): Int {
        if (idOrAll.equals("all", ignoreCase = true)) {
            val ids = FullscreenBroadcastManager.list().map { it.sessionId }
            ids.forEach(FullscreenBroadcastManager::stop)
            return ids.size
        }
        if (FullscreenBroadcastManager.stop(idOrAll)) return 1
        val displayId = runCatching { UUID.fromString(idOrAll) }.getOrNull() ?: return 0
        val sessionId = FullscreenBroadcastManager.sessionIdForDisplay(displayId) ?: return 0
        return if (FullscreenBroadcastManager.stop(sessionId)) 1 else 0
    }

    /** Live sessions for the `list` subcommand. */
    fun list(): List<FullscreenSessionInfo> = FullscreenBroadcastManager.list()

    /** Suggestion tokens for `/display fullscreen stop`: every live session id, plus `all`. */
    fun stopSuggestions(): List<String> = FullscreenBroadcastManager.list().map { it.sessionId } + "all"
}

/** Paper adapter: resolves Bukkit sender/player state and turns [FullscreenCommand] results into chat replies. */
@PaperOnly
object PaperFullscreenCommand {
    /** Handles `/display fullscreen start <id> [<flags>]`; player-only, since it needs a position for the default radius origin. */
    fun start(
        sender: CommandSender,
        id: String,
        players: String?,
        radiusBlocks: Double?,
        radiusX: Double?,
        radiusY: Double?,
        radiusZ: Double?,
        mode: String?,
        forced: Boolean,
        transientSession: Boolean,
        volume: Float?,
        loop: Boolean,
        quality: String?,
    ) {
        val player = sender as? Player ?: return
        val resolved = FullscreenBroadcastManager.resolveOrCreateDisplay(id, player.uniqueId)
            ?: return MessageUtil.sendMessage(sender, "fullscreenNoDisplay")
        val (display, virtual) = resolved
        val config = PaperServer.config.settings
        val result = FullscreenCommand.start(
            display = display,
            virtual = virtual,
            ownerId = player.uniqueId,
            mode = mode?.let { m -> runCatching { FullscreenMode.valueOf(m.uppercase()) }.getOrNull() },
            forced = forced,
            transientSession = transientSession,
            volume = volume,
            loop = loop,
            quality = quality,
            targetNamesRaw = players,
            radiusBlocks = radiusBlocks,
            radiusX = radiusX,
            radiusY = radiusY,
            radiusZ = radiusZ,
            defaultMode = config.fullscreenDefaultMode,
            allowForced = config.fullscreenAllowForced,
            senderWorld = player.world.name,
            senderX = player.location.x,
            senderY = player.location.y,
            senderZ = player.location.z,
            resolveTarget = { token -> resolveTargetToken(player, token) },
        )
        reply(sender, result)
    }

    /**
     * Expands one `target` token to the players it refers to: `@a` / `@e` (everyone online), `@s`
     * (the [sender] themselves), `@p` (nearest online player to the sender), `@r` (one random online
     * player), `%<group>` (everyone holding the `group.<group>` permission node — the same convention
     * `permissions.premium` already uses for `group.premium`), or an exact player name.
     */
    private fun resolveTargetToken(sender: Player, token: String): Set<UUID> = when {
        token.equals("@a", ignoreCase = true) || token.equals("@e", ignoreCase = true) ->
            Bukkit.getOnlinePlayers().map { it.uniqueId }.toSet()
        token.equals("@s", ignoreCase = true) -> setOf(sender.uniqueId)
        token.equals("@p", ignoreCase = true) ->
            Bukkit.getOnlinePlayers()
                .filter { it.world == sender.world }
                .minByOrNull { it.location.distanceSquared(sender.location) }
                ?.let { setOf(it.uniqueId) } ?: emptySet()
        token.equals("@r", ignoreCase = true) ->
            Bukkit.getOnlinePlayers().randomOrNull()?.let { setOf(it.uniqueId) } ?: emptySet()
        token.startsWith("%") ->
            Bukkit.getOnlinePlayers().filter { it.hasPermission("group.${token.substring(1)}") }.map { it.uniqueId }.toSet()
        else -> Bukkit.getPlayerExact(token)?.uniqueId?.let { setOf(it) } ?: emptySet()
    }

    /** Handles `/display fullscreen stop <sessionId|displayId|all>`. */
    fun stop(sender: CommandSender, idOrAll: String) {
        val count = FullscreenCommand.stop(idOrAll)
        if (count > 0) {
            MessageUtil.sendColoredMessage(sender, MessageUtil.formatIndexed(sender, "fullscreenStopped", count.toString()))
        } else {
            MessageUtil.sendMessage(sender, "fullscreenStopFailed")
        }
    }

    /** Handles `/display fullscreen list`. */
    fun list(sender: CommandSender) {
        val sessions = FullscreenCommand.list()
        if (sessions.isEmpty()) return MessageUtil.sendMessage(sender, "fullscreenListEmpty")
        sessions.forEach { s ->
            MessageUtil.sendColoredMessage(
                sender,
                MessageUtil.formatIndexed(
                    sender, "fullscreenListEntry",
                    s.sessionId, s.displayId.toString(), s.virtual.toString(), s.reach.toString(),
                ),
            )
        }
    }

    /** Online player names, for the `target` argument's suggestion list. */
    fun onlinePlayerNames(): List<String> = Bukkit.getOnlinePlayers().map { it.name }

    /** Suggestion tokens for `/display fullscreen stop`. */
    fun stopSuggestions(): List<String> = FullscreenCommand.stopSuggestions()

    private fun reply(sender: CommandSender, result: FullscreenStartResult) {
        when (result) {
            is FullscreenStartResult.Started -> MessageUtil.sendColoredMessage(
                sender,
                MessageUtil.formatIndexed(sender, "fullscreenStarted", result.sessionId, result.reach.toString()),
            )
            FullscreenStartResult.NoTargets -> MessageUtil.sendMessage(sender, "fullscreenNoTargets")
            FullscreenStartResult.AlreadyRunning -> MessageUtil.sendMessage(sender, "fullscreenAlreadyRunning")
            FullscreenStartResult.ForcedDisallowed -> MessageUtil.sendMessage(sender, "fullscreenForcedDisallowed")
        }
    }
}

/** Shared `Fabric` / `NeoForge` adapter: resolves vanilla sender/player state and turns [FullscreenCommand] results into chat replies. */
object VanillaFullscreenCommand {
    /** Handles `/display fullscreen start <id> [<flags>]`; player-only, since it needs a position for the default radius origin. */
    fun start(
        ctx: CommandContext<CommandSourceStack>,
        id: String,
        players: String?,
        radiusBlocks: Double?,
        radiusX: Double?,
        radiusY: Double?,
        radiusZ: Double?,
        mode: String?,
        forced: Boolean,
        transientSession: Boolean,
        volume: Float?,
        loop: Boolean,
        quality: String?,
    ): Int {
        val player = ctx.source.entity as? ServerPlayer ?: return 0
        val resolved = FullscreenBroadcastManager.resolveOrCreateDisplay(id, player.uuid) ?: run {
            MessageUtil.sendMessage(player, "fullscreenNoDisplay")
            return 0
        }
        val (display, virtual) = resolved
        val config = VanillaServerState.config.settings
        val result = FullscreenCommand.start(
            display = display,
            virtual = virtual,
            ownerId = player.uuid,
            mode = mode?.let { m -> runCatching { FullscreenMode.valueOf(m.uppercase()) }.getOrNull() },
            forced = forced,
            transientSession = transientSession,
            volume = volume,
            loop = loop,
            quality = quality,
            targetNamesRaw = players,
            radiusBlocks = radiusBlocks,
            radiusX = radiusX,
            radiusY = radiusY,
            radiusZ = radiusZ,
            defaultMode = config.fullscreenDefaultMode,
            allowForced = config.fullscreenAllowForced,
            senderWorld = RegionUtil.getPlayerLevelKey(player),
            senderX = player.x,
            senderY = player.y,
            senderZ = player.z,
            resolveTarget = { token -> resolveTargetToken(ctx.source.server.playerList.players, player, token) },
        )
        reply(player, result)
        return 1
    }

    /**
     * Expands one `target` token to the players it refers to: `@a`/`@e` (everyone online), `@s`
     * (the [sender] themselves), `@p` (nearest online player to the sender), `@r` (one random online
     * player), `%<group>` (everyone holding the `group.<group>` permission node - the same convention
     * `permissions.premium` already uses for `group.premium`), or an exact player name.
     */
    private fun resolveTargetToken(online: List<ServerPlayer>, sender: ServerPlayer, token: String): Set<UUID> = when {
        token.equals("@a", ignoreCase = true) || token.equals("@e", ignoreCase = true) ->
            online.map { it.uuid }.toSet()
        token.equals("@s", ignoreCase = true) -> setOf(sender.uuid)
        token.equals("@p", ignoreCase = true) ->
            online
                .filter { it.level() == sender.level() }
                .minByOrNull { it.distanceToSqr(sender) }
                ?.let { setOf(it.uuid) } ?: emptySet()
        token.equals("@r", ignoreCase = true) -> online.randomOrNull()?.let { setOf(it.uuid) } ?: emptySet()
        token.startsWith("%") ->
            online.filter { VanillaPermissions.has(it, "group.${token.substring(1)}", VanillaPermissions.Fallback.NOBODY) }
                .map { it.uuid }.toSet()
        else -> online.firstOrNull { it.gameProfile.name.equals(token, ignoreCase = true) }?.uuid?.let { setOf(it) } ?: emptySet()
    }

    /** Handles `/display fullscreen stop <sessionId|displayId|all>`. */
    fun stop(ctx: CommandContext<CommandSourceStack>, idOrAll: String): Int {
        val player = ctx.source.entity as? ServerPlayer
        val count = FullscreenCommand.stop(idOrAll)
        val key = if (count > 0) "fullscreenStopped" else "fullscreenStopFailed"
        val line = MessageUtil.formatIndexed(player, key, count.toString())
        if (player != null) MessageUtil.sendColoredMessage(player, line) else ctx.source.sendSystemMessage(Component.literal(line))
        return count
    }

    /** Handles `/display fullscreen list`. */
    fun list(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer
        val sessions = FullscreenCommand.list()
        if (sessions.isEmpty()) {
            val line = MessageUtil.messageFor(player, "fullscreenListEmpty")
            if (player != null) MessageUtil.sendColoredMessage(player, line) else ctx.source.sendSystemMessage(Component.literal(line))
            return 0
        }
        sessions.forEach { s ->
            val line = MessageUtil.formatIndexed(
                player, "fullscreenListEntry",
                s.sessionId, s.displayId.toString(), s.virtual.toString(), s.reach.toString(),
            )
            if (player != null) MessageUtil.sendColoredMessage(player, line) else ctx.source.sendSystemMessage(Component.literal(line))
        }
        return sessions.size
    }

    /** Online player names, for the `target` argument's suggestion list. */
    fun onlinePlayerNames(ctx: CommandContext<CommandSourceStack>): List<String> =
        ctx.source.server.playerList.players.map { it.gameProfile.name }

    /** Suggestion tokens for `/display fullscreen stop`. */
    fun stopSuggestions(): List<String> = FullscreenCommand.stopSuggestions()

    private fun reply(player: ServerPlayer, result: FullscreenStartResult) {
        when (result) {
            is FullscreenStartResult.Started -> MessageUtil.sendColoredMessage(
                player,
                MessageUtil.formatIndexed(player, "fullscreenStarted", result.sessionId, result.reach.toString()),
            )
            FullscreenStartResult.NoTargets -> MessageUtil.sendMessage(player, "fullscreenNoTargets")
            FullscreenStartResult.AlreadyRunning -> MessageUtil.sendMessage(player, "fullscreenAlreadyRunning")
            FullscreenStartResult.ForcedDisallowed -> MessageUtil.sendMessage(player, "fullscreenForcedDisallowed")
        }
    }
}
