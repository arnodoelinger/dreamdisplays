package com.dreamdisplays.platform.server.registrar

import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.platform.server.PermissionsSection
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.commands.subcommands.*
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.proxy.ProxyNetwork
import com.dreamdisplays.platform.server.registrar.VanillaCommandTree.fullscreenFlagsNode
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ScheduleTimeUtil
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.mojang.brigadier.Command
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.server.level.ServerPlayer
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Shared `Fabric` / `NeoForge` `/display` command tree. See `CommandRegistrar.kt` for the
 * `Paper` equivalent (built on `io.papermc.paper.command.brigadier` wrapper types of the same
 * simple names; imports are per-file in Kotlin so the two coexist without collision).
 */
@ModLoaderOnly
object VanillaCommandTree {
    /** Suggestion tokens for the fullscreen `quality` flag. */
    private val QUALITY_SUGGESTIONS = listOf("auto", "360", "480", "720", "1080")

    /** Selector tokens suggested for the fullscreen `target` argument, alongside online player names. */
    private val TARGET_SELECTORS = listOf("@a", "@p", "@r", "@s", "@e")

    /** Builds the full `/display` command tree, ready to attach to a dispatcher root. */
    fun build(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("display")
            .executes { ctx ->
                VanillaHelpCommand.execute(ctx)
                Command.SINGLE_SUCCESS
            }
            .then(helpNode())
            .then(createNode())
            .then(deleteNode())
            .then(infoNode())
            .then(listNode())
            .then(statsNode())
            .then(reloadNode())
            .then(videoNode())
            .then(nameNode())
            .then(scheduleNode())
            .then(toggleNode("on"))
            .then(toggleNode("off"))
            .then(fullscreenNode())
            .build()

    /** Builds the `/display help` subcommand. */
    private fun helpNode() = Commands.literal("help")
        .executes { ctx ->
            VanillaHelpCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

    /** Builds the `/display create` subcommand. */
    private fun createNode() = Commands.literal("create")
        .requires { requiresNode(it, { p -> p.create }, VanillaPermissions.Fallback.EVERYONE) }
        .executes { ctx ->
            VanillaCreateCommand.execute(ctx)
        }

    /**
     * Builds the `/display delete this|<id>` subcommand. `this` resolves via raycast, matching every other
     * `this`-accepting node in this tree.
     */
    private fun deleteNode() = Commands.literal("delete")
        .then(
            Commands.literal("this").executes { ctx ->
                VanillaDeleteCommand.execute(ctx, "this")
                Command.SINGLE_SUCCESS
            }
        )
        .then(
            Commands.argument("id", BareTokenArgumentType)
                .suggests { _, b ->
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }
                    b.buildFuture()
                }
                .executes { ctx ->
                    VanillaDeleteCommand.execute(ctx, StringArgumentType.getString(ctx, "id"))
                    Command.SINGLE_SUCCESS
                }
        )

    /** Builds the `/display info this|<id>` subcommand — see [deleteNode] for `this` / id semantics. */
    private fun infoNode() = Commands.literal("info")
        .requires { requiresNode(it, { p -> p.info }, VanillaPermissions.Fallback.EVERYONE) }
        .then(
            Commands.literal("this").executes { ctx ->
                VanillaInfoCommand.execute(ctx, "this")
                Command.SINGLE_SUCCESS
            }
        )
        .then(
            Commands.argument("id", BareTokenArgumentType)
                .suggests { _, b ->
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }
                    b.buildFuture()
                }
                .executes { ctx ->
                    VanillaInfoCommand.execute(ctx, StringArgumentType.getString(ctx, "id"))
                    Command.SINGLE_SUCCESS
                }
        )

    /** Builds the `/display stats` subcommand. */
    private fun statsNode() = Commands.literal("stats")
        .requires { requiresNode(it, { p -> p.stats }, VanillaPermissions.Fallback.OP) }
        .executes { ctx ->
            VanillaStatsCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

    /** Builds the `/display reload` subcommand. */
    private fun reloadNode() = Commands.literal("reload")
        .requires { requiresNode(it, { p -> p.reload }, VanillaPermissions.Fallback.OP) }
        .executes { ctx ->
            VanillaReloadCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

    /** Builds the `/display video this|<id> <url> [lang]` subcommand — see [deleteNode] for `this`/id semantics. */
    private fun videoNode() = Commands.literal("video")
        .requires { requiresNode(it, { p -> p.video }, VanillaPermissions.Fallback.EVERYONE) }
        .then(Commands.literal("this").then(videoUrlArgument { "this" }))
        .then(
            Commands.argument("id", BareTokenArgumentType)
                .suggests { _, b ->
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }
                    b.buildFuture()
                }
                .then(videoUrlArgument { ctx -> StringArgumentType.getString(ctx, "id") })
        )

    /** The `<url> [lang]` greedy argument under `video this|<id> <url> [lang]`. */
    private fun videoUrlArgument(token: (CommandContext<CommandSourceStack>) -> String) =
        Commands.argument("url_and_lang", StringArgumentType.greedyString())
            .suggests { _, builder ->
                if (builder.remaining.contains(' ')) {
                    val prefix = builder.remaining.substringAfterLast(' ')
                    getLanguageSuggestions()
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                        .forEach { builder.suggest(builder.remaining.substringBeforeLast(' ') + " " + it) }
                }
                builder.buildFuture()
            }
            .executes { ctx ->
                val urlAndLang = StringArgumentType.getString(ctx, "url_and_lang")
                VanillaVideoCommand.execute(ctx, token(ctx), urlAndLang)
                Command.SINGLE_SUCCESS
            }

    /**
     * Builds the `/display name this|<id> [name]` subcommand — see [deleteNode] for `this` / id semantics.
     * `name` is optional; omitting it clears the display's name.
     */
    private fun nameNode() = Commands.literal("name")
        .requires { requiresNode(it, { p -> p.name }, VanillaPermissions.Fallback.EVERYONE) }
        .then(
            Commands.literal("this")
                .executes { ctx -> VanillaNameCommand.execute(ctx, "this", null) }
                .then(nameArgument { "this" })
        )
        .then(
            Commands.argument("id", BareTokenArgumentType)
                .suggests { _, b ->
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }
                    b.buildFuture()
                }
                .executes { ctx -> VanillaNameCommand.execute(ctx, StringArgumentType.getString(ctx, "id"), null) }
                .then(nameArgument { ctx -> StringArgumentType.getString(ctx, "id") })
        )

    /** The `<name>` argument under `name this|<id> <name>`. */
    private fun nameArgument(token: (CommandContext<CommandSourceStack>) -> String) =
        Commands.argument("name", StringArgumentType.word())
            .executes { ctx ->
                VanillaNameCommand.execute(ctx, token(ctx), StringArgumentType.getString(ctx, "name"))
            }

    /**
     * Builds the `/display schedule this|<id> [play|pause|cancel] [<HH:mm[:ss]>]` subcommand.
     */
    private fun scheduleNode() = Commands.literal("schedule")
        .requires { requiresNode(it, { p -> p.schedule }, VanillaPermissions.Fallback.EVERYONE) }
        .then(
            Commands.literal("this")
                .executes { ctx -> VanillaScheduleCommand.execute(ctx, "this", null, null) }
                .then(
                    Commands.literal("cancel")
                        .executes { ctx -> VanillaScheduleCommand.execute(ctx, "this", "cancel", null) })
                .then(scheduleActionNode("play") { "this" })
                .then(scheduleActionNode("pause") { "this" })
        )
        .then(
            Commands.argument("id", BareTokenArgumentType)
                .suggests { _, b ->
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }
                    b.buildFuture()
                }
                .executes { ctx ->
                    VanillaScheduleCommand.execute(
                        ctx,
                        StringArgumentType.getString(ctx, "id"),
                        null,
                        null
                    )
                }
                .then(
                    Commands.literal("cancel").executes { ctx ->
                        VanillaScheduleCommand.execute(ctx, StringArgumentType.getString(ctx, "id"), "cancel", null)
                    }
                )
                .then(scheduleActionNode("play") { ctx -> StringArgumentType.getString(ctx, "id") })
                .then(scheduleActionNode("pause") { ctx -> StringArgumentType.getString(ctx, "id") })
        )

    /**
     * The `play` / `pause` literal under `schedule this|<id>`: bare (no time yet) reveals the player's
     * current local time so they have an anchor to schedule against, and offers the `<HH:mm[:ss]>`
     * argument to actually set it.
     */
    private fun scheduleActionNode(action: String, token: (CommandContext<CommandSourceStack>) -> String) =
        Commands.literal(action)
            .executes { ctx -> VanillaScheduleCommand.execute(ctx, token(ctx), action, null) }
            .then(scheduleTimeArgument(action, token))

    /**
     * The `<HH:mm[:ss]>` argument under `schedule this|<id> play|pause <HH:mm[:ss]>`, suggesting
     * every minute of the player's local day. Uses [BareTokenArgumentType] (not
     * [StringArgumentType.word]) since `:` isn't in `word()`'s allowed unquoted charset.
     */
    private fun scheduleTimeArgument(action: String, token: (CommandContext<CommandSourceStack>) -> String) =
        Commands.argument("time", BareTokenArgumentType)
            .suggests { ctx, builder -> scheduleTimeSuggestions(ctx.source.entity as? ServerPlayer, builder) }
            .executes { ctx ->
                VanillaScheduleCommand.execute(ctx, token(ctx), action, StringArgumentType.getString(ctx, "time"))
            }

    /**
     * Suggests every minute-of-day as `HH:mm`, player-local (via [ScheduleTimeUtil]).
     */
    private fun scheduleTimeSuggestions(
        player: ServerPlayer?,
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {
        val offset = player?.let { ScheduleTimeUtil.offsetMinutesOf(it.uuid) } ?: 0
        val nowMinute = ScheduleTimeUtil.minuteOfDay(ScheduleTimeUtil.currentSecondOfDay(offset))
        val firstMinute = (nowMinute + 1) % 1440
        val prefix = builder.remaining

        val candidateMinutes = if (prefix.isBlank()) {
            (0 until 120).map { (firstMinute + it) % 1440 }
        } else {
            (0 until 1440)
                .map { (firstMinute + it) % 1440 }
                .filter { ScheduleTimeUtil.format(it * 60).startsWith(prefix, ignoreCase = true) }
                .take(150)
        }

        candidateMinutes.forEach { minute ->
            val text = ScheduleTimeUtil.format(minute * 60)
            val secondsAhead = ScheduleTimeUtil.secondsUntil(minute * 60, offset)
            val tooltip = if (minute == firstMinute) "now" else ScheduleTimeUtil.compactCountdown(secondsAhead)
            builder.suggest(text, LiteralMessage(tooltip))
        }
        return builder.buildFuture()
    }

    /** Builds the `/display list [filter] [value] [page]` subcommand. */
    private fun listNode(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("list")
            .requires { requiresNode(it, { p -> p.list }, VanillaPermissions.Fallback.OP) }
            .executes { ctx ->
                VanillaListCommand.execute(ctx)
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("filter", StringArgumentType.word())
                    .suggests { _, builder ->
                        ListFilter.tokens.forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val filter = StringArgumentType.getString(ctx, "filter")
                        VanillaListCommand.execute(ctx, filter = filter)
                        Command.SINGLE_SUCCESS
                    }
                    .then(
                        Commands.argument("value", StringArgumentType.word())
                            .executes { ctx ->
                                val filter = StringArgumentType.getString(ctx, "filter")
                                val value = StringArgumentType.getString(ctx, "value")
                                VanillaListCommand.execute(ctx, filter = filter, value = value)
                                Command.SINGLE_SUCCESS
                            }
                            .then(
                                Commands.argument("page", StringArgumentType.word())
                                    .executes { ctx ->
                                        val filter = StringArgumentType.getString(ctx, "filter")
                                        val value = StringArgumentType.getString(ctx, "value")
                                        val page = StringArgumentType.getString(ctx, "page")
                                        VanillaListCommand.execute(ctx, filter = filter, value = value, pageStr = page)
                                        Command.SINGLE_SUCCESS
                                    }
                            )
                    )
            )
    }

    /** Builds the `/display on/off [player]` subcommand. */
    private fun toggleNode(name: String) = Commands.literal(name)
        .executes { ctx ->
            when (name) {
                "on" -> VanillaOnCommand.execute(ctx)
                "off" -> VanillaOffCommand.execute(ctx)
            }
            Command.SINGLE_SUCCESS
        }
        .then(
            Commands.argument("player", StringArgumentType.word())
                .requires { requiresNode(it, { p -> p.toggleOthers }, VanillaPermissions.Fallback.OP) }
                .suggests { ctx, builder ->
                    ctx.source.server.playerList.players.forEach { builder.suggest(it.name.string) }
                    builder.buildFuture()
                }
                .executes { ctx ->
                    val targetName = StringArgumentType.getString(ctx, "player")
                    when (name) {
                        "on" -> VanillaOnCommand.execute(ctx, targetName)
                        "off" -> VanillaOffCommand.execute(ctx, targetName)
                    }
                    Command.SINGLE_SUCCESS
                }
        )

    /**
     * Builds the `/display fullscreen start|stop|list` subcommand: server-forced fullscreen
     * broadcasts to players by name selector, radius, or both (combinable).
     */
    private fun fullscreenNode() = Commands.literal("fullscreen")
        .executes { ctx ->
            val player = ctx.source.entity as? ServerPlayer
            MessageUtil.sendColoredMessage(
                player,
                VanillaServerState.config.getMessageForPlayer(player, "displayHelpFullscreen")
            )
            Command.SINGLE_SUCCESS
        }
        .then(fullscreenStartNode())
        .then(fullscreenStopNode())
        .then(fullscreenListNode())

    /**
     * `/display fullscreen start <id <id>|url <url>> [<flags in any order/combination>]`, flags being `target <players>`,
     * `scope`, `force`, and the like — see [fullscreenFlagsNode].
     */
    private fun fullscreenStartNode(): LiteralArgumentBuilder<CommandSourceStack> {
        val flags = fullscreenFlagsNode()
        return Commands.literal("start")
            .requires { requiresNode(it, { p -> p.fullscreenStart }, VanillaPermissions.Fallback.OP) }
            .then(fullscreenIdOrUrlNode("id", flags))
            .then(fullscreenIdOrUrlNode("url", flags))
    }

    /** Builds the `id <id>` / `url <url>` branch under `/display fullscreen start`, both feeding the same `id` argument. */
    private fun fullscreenIdOrUrlNode(
        literalName: String,
        flags: List<CommandNode<CommandSourceStack>>,
    ) = Commands.literal(literalName).then(
        Commands.argument("id", BareTokenArgumentType)
            .suggests { _, builder ->
                if (literalName == "id") {
                    builder.suggest("this")
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { builder.suggest(it) }
                }
                builder.buildFuture()
            }
            .executes { ctx -> runFullscreenStart(ctx) }
            .also { idArg -> flags.forEach { idArg.then(it) } }
    )

    /**
     * The fullscreen-start flags, in the one order they may be given in. `server` comes first: it
     * picks the broadest scope (which backend, or the whole network) before `target` / `radius`
     * narrow who within that scope actually sees it.
     */
    private val FULLSCREEN_FLAGS =
        listOf("server", "target", "radius", "mode", "forced", "transient", "volume", "looped", "quality")

    /**
     * All fullscreen-start flags, any subset of them accepted but only in [FULLSCREEN_FLAGS] order:
     * each flag's subtree offers just the flags that follow it.
     */
    private fun fullscreenFlagsNode(): List<CommandNode<CommandSourceStack>> {
        var following = emptyList<CommandNode<CommandSourceStack>>()
        for (name in FULLSCREEN_FLAGS.asReversed()) {
            following = listOf(buildFullscreenFlagNode(name, following)) + following
        }
        return following
    }

    /** Attaches [children] plus a bare `.executes` (this flag alone, nothing further) to [node], then builds it. */
    private fun terminate(
        node: ArgumentBuilder<CommandSourceStack, *>,
        children: List<CommandNode<CommandSourceStack>>,
    ): CommandNode<CommandSourceStack> {
        node.executes { ctx -> runFullscreenStart(ctx) }
        children.forEach { node.then(it) }
        return node.build()
    }

    /** Builds one flag's own literal / argument subtree, attaching the already-built [children] at every terminal. */
    private fun buildFullscreenFlagNode(
        name: String,
        children: List<CommandNode<CommandSourceStack>>
    ): CommandNode<CommandSourceStack> =
        when (name) {
            "server" -> Commands.literal("server")
                .requires { requiresNode(it, { p -> p.fullscreenNetwork }, VanillaPermissions.Fallback.OP) }
                .then(
                    terminate(
                        Commands.argument("name", StringArgumentType.word())
                            .suggests { _, builder ->
                                (ProxyNetwork.serverNames() + "global")
                                    .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                                    .forEach { builder.suggest(it) }
                                builder.buildFuture()
                            },
                        children,
                    )
                ).build()

            "target" -> Commands.literal("target").then(
                terminate(
                    Commands.argument("players", BareTokenArgumentType)
                        .suggests { ctx, builder -> suggestPlayerNames(ctx, builder) },
                    children,
                )
            ).build()

            "radius" -> Commands.literal("radius").then(
                terminate(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.0)), children).also { blocks ->
                    blocks.addChild(
                        Commands.argument("x", DoubleArgumentType.doubleArg())
                            .then(
                                Commands.argument("y", DoubleArgumentType.doubleArg())
                                    .then(terminate(Commands.argument("z", DoubleArgumentType.doubleArg()), children))
                            )
                            .build()
                    )
                }
            ).build()

            "mode" -> Commands.literal("mode")
                .then(terminate(Commands.literal("standard"), children))
                .then(terminate(Commands.literal("immersive"), children))
                .build()

            "forced" -> terminate(Commands.literal("forced"), children)
            "transient" -> terminate(Commands.literal("transient"), children)
            "volume" -> Commands.literal("volume").then(
                terminate(Commands.argument("volume", DoubleArgumentType.doubleArg(0.0, 200.0)), children)
            ).build()

            "looped" -> terminate(Commands.literal("looped"), children)
            "quality" -> Commands.literal("quality").then(
                terminate(
                    Commands.argument("quality", StringArgumentType.word())
                        .suggests { _, builder ->
                            QUALITY_SUGGESTIONS.forEach { builder.suggest(it) }
                            builder.buildFuture()
                        },
                    children,
                )
            ).build()

            else -> error("Unknown fullscreen flag: $name")
        }

    /** Reads [name] from [ctx] if that argument was part of the parsed path, else null. */
    private fun <T : Any> tryArg(ctx: CommandContext<CommandSourceStack>, name: String, type: Class<T>): T? =
        runCatching { ctx.getArgument(name, type) }.getOrNull()

    /** Gathers every flag argument present on the parsed [ctx] path and delegates to [VanillaFullscreenCommand.start]. */
    private fun runFullscreenStart(ctx: CommandContext<CommandSourceStack>): Int {
        val nodeNames = ctx.nodes.map { it.node.name }
        val mode = when {
            "standard" in nodeNames -> "standard"
            "immersive" in nodeNames -> "immersive"
            else -> null
        }
        return VanillaFullscreenCommand.start(
            ctx,
            id = StringArgumentType.getString(ctx, "id"),
            serverScope = tryArg(ctx, "name", String::class.java),
            players = tryArg(ctx, "players", String::class.java),
            radiusBlocks = tryArg(ctx, "blocks", Double::class.javaObjectType),
            radiusX = tryArg(ctx, "x", Double::class.javaObjectType),
            radiusY = tryArg(ctx, "y", Double::class.javaObjectType),
            radiusZ = tryArg(ctx, "z", Double::class.javaObjectType),
            mode = mode,
            forced = "forced" in nodeNames,
            transientSession = "transient" in nodeNames,
            volume = tryArg(ctx, "volume", Double::class.javaObjectType)?.let { (it.toFloat() / 200f) },
            loop = "looped" in nodeNames,
            quality = tryArg(ctx, "quality", String::class.java),
        )
    }

    /** Suggests online player names for the last comma-separated fragment of the `players` argument. */
    private fun suggestPlayerNames(
        ctx: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val remaining = builder.remaining
        val prefix = remaining.substringAfterLast(',')
        val before = remaining.substringBeforeLast(',', "").let { if (it.isEmpty()) "" else "$it," }
        (TARGET_SELECTORS + VanillaFullscreenCommand.onlinePlayerNames(ctx))
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .forEach { builder.suggest(before + it) }
        return builder.buildFuture()
    }

    /** `/display fullscreen stop <sessionId|displayId|all>`, suggesting live session/display ids. */
    private fun fullscreenStopNode() = Commands.literal("stop")
        .requires { requiresNode(it, { p -> p.fullscreenStop }, VanillaPermissions.Fallback.OP) }
        .then(
            Commands.argument("id", StringArgumentType.word())
                .suggests { _, builder ->
                    VanillaFullscreenCommand.stopSuggestions().forEach { builder.suggest(it) }
                    builder.buildFuture()
                }
                .executes { ctx -> VanillaFullscreenCommand.stop(ctx, StringArgumentType.getString(ctx, "id")) }
        )

    /** `/display fullscreen list`. */
    private fun fullscreenListNode() = Commands.literal("list")
        .requires { requiresNode(it, { p -> p.fullscreenList }, VanillaPermissions.Fallback.OP) }
        .executes { ctx -> VanillaFullscreenCommand.list(ctx) }

    /** Permission gate shared by every node: console always passes, players are checked against [node]. */
    private fun requiresNode(
        source: CommandSourceStack,
        node: (PermissionsSection) -> String,
        fallback: VanillaPermissions.Fallback,
    ): Boolean {
        val player = source.entity as? ServerPlayer
        return player == null || VanillaPermissions.has(player, node(VanillaServerState.config.permissions), fallback)
    }

    /** Returns a list of language codes from Java and config. */
    private fun getLanguageSuggestions(): List<String> {
        val fromJava = Locale.getAvailableLocales()
            .map { it.language.lowercase(Locale.ROOT) }
        val fromConfig = VanillaServerState.config.languages.keys
            .map { it.trim().lowercase(Locale.ROOT).substringBefore('_') }
        return (fromJava + fromConfig)
            .filter { it.matches(Regex("^[a-z]{2}$")) }
            .map { if (it == "uk") "ua" else it }
            .distinct()
            .sorted()
    }
}
