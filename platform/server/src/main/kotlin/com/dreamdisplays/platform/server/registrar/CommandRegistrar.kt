package com.dreamdisplays.platform.server.registrar

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.commands.subcommands.*
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.proxy.ProxyNetwork
import com.dreamdisplays.platform.server.registrar.CommandRegistrar.fullscreenFlagsNode
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ScheduleTimeUtil
import com.mojang.brigadier.Command
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.arnodoelinger.platformweaver.PaperOnly
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.CustomArgumentType
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

/**
 * Command registrar. Uses `Brigadier` to build the `/display` command tree. See
 * `VanillaCommandTree.kt` for the shared `Fabric` / `NeoForge` equivalent.
 */
@PaperOnly
object CommandRegistrar {
    /** Suggestion tokens for the fullscreen `quality` flag. */
    private val QUALITY_SUGGESTIONS = listOf("auto", "360", "480", "720", "1080")

    /** Selector tokens suggested for the fullscreen `target` argument, alongside online player names. */
    private val TARGET_SELECTORS = listOf("@a", "@p", "@r", "@s", "@e")

    /**
     * The fullscreen-start flags, in the one order they may be given in. `server` comes first: it
     * picks the broadest scope (which backend, or the whole network) before `target`/`radius`
     * narrow who within that scope actually sees it.
     */
    private val FULLSCREEN_FLAGS =
        listOf("server", "target", "radius", "mode", "forced", "transient", "volume", "looped", "quality")

    /** Builds the full `Brigadier` tree for the `/display` command with all subcommands. */
    fun buildDisplayCommand(): LiteralCommandNode<CommandSourceStack> = Commands.literal("display")
        .executes { ctx ->
            HelpCommand().execute(ctx.source.sender, emptyArray())
            Command.SINGLE_SUCCESS
        }
        .then(simple("help", HelpCommand()))
        .then(
            simple(
                "create",
                CreateCommand()
            ) { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.create) })
        .then(
            simpleWithThis(
                "delete",
                DeleteCommand()
            ) { it.sender is Player })
        .then(
            simpleWithThis(
                "info",
                InfoCommand()
            ) { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.info) })
        .then(simple("stats", StatsCommand()) { it.sender.hasPermission(PaperServer.config.permissions.stats) })
        .then(simple("reload", ReloadCommand()) { it.sender.hasPermission(PaperServer.config.permissions.reload) })
        .then(videoSubCommand())
        .then(nameSubCommand())
        .then(scheduleSubCommand())
        .then(listSubCommand())
        .then(toggleSubCommand("on", OnCommand()))
        .then(toggleSubCommand("off", OffCommand()))
        .then(fullscreenSubCommand())
        .build()

    /** Builds a simple no-argument subcommand node optionally guarded by a permission check. */
    private fun simple(
        name: String,
        cmd: SubCommand,
        check: ((CommandSourceStack) -> Boolean)? = null,
    ): LiteralArgumentBuilder<CommandSourceStack> {
        var builder = Commands.literal(name)
        if (check != null) builder = builder.requires(check)
        return builder.executes { ctx ->
            cmd.execute(ctx.source.sender, emptyArray())
            Command.SINGLE_SUCCESS
        }
    }

    /** Like [simple], but adds branches for `this` (raycast) and an explicit id argument. */
    private fun simpleWithThis(
        name: String,
        cmd: SubCommand,
        check: ((CommandSourceStack) -> Boolean)? = null,
    ): LiteralArgumentBuilder<CommandSourceStack> {
        var builder = Commands.literal(name)
        if (check != null) builder = builder.requires(check)
        return builder
            .then(
                Commands.literal("this").executes { ctx ->
                    cmd.execute(ctx.source.sender, arrayOf("this"))
                    Command.SINGLE_SUCCESS
                }
            )
            .then(
                Commands.argument("id", PaperBareTokenArgumentType)
                    .suggests { _, b ->
                        FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }
                        b.buildFuture()
                    }
                    .executes { ctx ->
                        cmd.execute(ctx.source.sender, arrayOf(StringArgumentType.getString(ctx, "id")))
                        Command.SINGLE_SUCCESS
                    }
            )
    }

    /**
     * Builds the `/display video this|<id> <url> [lang]` subcommand — see [simpleWithThis] for why
     * both `this` and an explicit id are offered.
     */
    private fun videoSubCommand() = Commands.literal("video")
        .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.video) }
        .then(Commands.literal("this").then(videoUrlArgument { "this" }))
        .then(
            Commands.argument("id", PaperBareTokenArgumentType)
                .suggests { _, b ->
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }
                    b.buildFuture()
                }
                .then(videoUrlArgument { ctx -> StringArgumentType.getString(ctx, "id") })
        )

    /** The `<url> [lang]` greedy argument under `/display video this|<id> <url> [lang]`. */
    private fun videoUrlArgument(token: (CommandContext<CommandSourceStack>) -> String) =
        // greedyString captures the rest of the input (URL + optional lang separated by space)
        Commands.argument("url_and_lang", StringArgumentType.greedyString())
            .suggests { _, builder ->
                // Only suggest lang codes when the input looks like "url lang_prefix"
                if (builder.remaining.contains(' ')) {
                    val prefix = builder.remaining.substringAfterLast(' ')
                    VideoCommand.languageSuggestions
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                        .forEach { builder.suggest(builder.remaining.substringBeforeLast(' ') + " " + it) }
                }
                builder.buildFuture()
            }
            .executes { ctx ->
                val raw = StringArgumentType.getString(ctx, "url_and_lang").trim()
                val parts = raw.split(" ")
                val url = parts[0]
                val lang = if (parts.size > 1) parts.last() else ""
                VideoCommand().execute(ctx.source.sender, arrayOf(token(ctx), url, lang))
                Command.SINGLE_SUCCESS
            }

    /** Builds the `/display name this|<id> [name]` subcommand with optional name argument. */
    private fun nameSubCommand() = Commands.literal("name")
        .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.name) }
        .then(
            Commands.literal("this")
                .executes { ctx ->
                    NameCommand().execute(ctx.source.sender, arrayOf("this"))
                    Command.SINGLE_SUCCESS
                }
                .then(nameArgument { "this" })
        )
        .then(
            Commands.argument("id", PaperBareTokenArgumentType)
                .suggests { _, b ->
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }
                    b.buildFuture()
                }
                .executes { ctx ->
                    NameCommand().execute(ctx.source.sender, arrayOf(StringArgumentType.getString(ctx, "id")))
                    Command.SINGLE_SUCCESS
                }
                .then(nameArgument { ctx -> StringArgumentType.getString(ctx, "id") })
        )

    /** The `<name>` argument under `/display name this|<id> <name>`. */
    private fun nameArgument(token: (CommandContext<CommandSourceStack>) -> String) =
        Commands.argument("name", StringArgumentType.word())
            .executes { ctx ->
                NameCommand().execute(
                    ctx.source.sender,
                    arrayOf(token(ctx), StringArgumentType.getString(ctx, "name"))
                )
                Command.SINGLE_SUCCESS
            }

    /**
     * Builds the `/display schedule this|<id> [play|pause|cancel] [<HH:mm[:ss]>]` subcommand.
     */
    private fun scheduleSubCommand() = Commands.literal("schedule")
        .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.schedule) }
        .then(
            Commands.literal("this")
                .executes { ctx ->
                    ScheduleCommand().execute(ctx.source.sender, arrayOf("this", null, null))
                    Command.SINGLE_SUCCESS
                }
                .then(
                    Commands.literal("cancel").executes { ctx ->
                        ScheduleCommand().execute(ctx.source.sender, arrayOf("this", "cancel", null))
                        Command.SINGLE_SUCCESS
                    }
                )
                .then(scheduleActionNode("play") { "this" })
                .then(scheduleActionNode("pause") { "this" })
        )
        .then(
            Commands.argument("id", PaperBareTokenArgumentType)
                .suggests { _, b ->
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }
                    b.buildFuture()
                }
                .executes { ctx ->
                    ScheduleCommand().execute(
                        ctx.source.sender,
                        arrayOf(StringArgumentType.getString(ctx, "id"), null, null),
                    )
                    Command.SINGLE_SUCCESS
                }
                .then(
                    Commands.literal("cancel").executes { ctx ->
                        ScheduleCommand().execute(
                            ctx.source.sender,
                            arrayOf(StringArgumentType.getString(ctx, "id"), "cancel", null),
                        )
                        Command.SINGLE_SUCCESS
                    }
                )
                .then(scheduleActionNode("play") { ctx -> StringArgumentType.getString(ctx, "id") })
                .then(scheduleActionNode("pause") { ctx -> StringArgumentType.getString(ctx, "id") })
        )

    /**
     * The `play`/`pause` literal under `/display schedule this|<id>`: bare (no time yet) reveals the
     * player's current local time so they have an anchor to schedule against, and offers the
     * `<HH:mm[:ss]>` argument to actually set it.
     */
    private fun scheduleActionNode(action: String, token: (CommandContext<CommandSourceStack>) -> String) =
        Commands.literal(action)
            .executes { ctx ->
                ScheduleCommand().execute(ctx.source.sender, arrayOf(token(ctx), action, null))
                Command.SINGLE_SUCCESS
            }
            .then(scheduleTimeArgument(action, token))

    /**
     * The `<HH:mm[:ss]>` argument under `/display schedule this|<id> play|pause <HH:mm[:ss]>`,
     * suggesting every minute of the player's local day.
     */
    private fun scheduleTimeArgument(action: String, token: (CommandContext<CommandSourceStack>) -> String) =
        Commands.argument("time", PaperBareTokenArgumentType)
            .suggests { ctx, builder -> scheduleTimeSuggestions(ctx.source.sender as? Player, builder) }
            .executes { ctx ->
                ScheduleCommand().execute(
                    ctx.source.sender,
                    arrayOf(token(ctx), action, StringArgumentType.getString(ctx, "time")),
                )
                Command.SINGLE_SUCCESS
            }

    /**
     * Suggests every minute-of-day as `HH:mm`, player-local (via [ScheduleTimeUtil]). With nothing
     * typed yet, the first entry is "now" (rounded to the next minute) followed by a rolling window
     * of the next two hours.
     */
    private fun scheduleTimeSuggestions(player: Player?, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val offset = player?.let { ScheduleTimeUtil.offsetMinutesOf(it.uniqueId) } ?: 0
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

    /** Builds an on / off toggle subcommand that optionally targets another player. */
    private fun toggleSubCommand(name: String, cmd: SubCommand) = Commands.literal(name)
        .executes { ctx ->
            cmd.execute(ctx.source.sender, arrayOf(name))
            Command.SINGLE_SUCCESS
        }
        .then(
            Commands.argument("player", StringArgumentType.word())
                .requires { it.sender.hasPermission(PaperServer.config.permissions.toggleOthers) }
                .suggests { _, builder ->
                    Bukkit.getOnlinePlayers().forEach { builder.suggest(it.name) }
                    builder.buildFuture()
                }
                .executes { ctx ->
                    val playerName = StringArgumentType.getString(ctx, "player")
                    cmd.execute(ctx.source.sender, arrayOf(name, playerName))
                    Command.SINGLE_SUCCESS
                }
        )

    /**
     * Builds the `/display fullscreen start|stop|list` subcommand: server-forced fullscreen
     * broadcasts to players by name selector, radius, or both (combinable).
     */
    private fun fullscreenSubCommand(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("fullscreen")
        .executes { ctx ->
            (ctx.source.sender as? Player)?.let { player ->
                MessageUtil.sendColoredMessage(
                    ctx.source.sender,
                    "&f ${PaperServer.config.getMessageForPlayer(player, "displayHelpFullscreen")}"
                )
            }
            Command.SINGLE_SUCCESS
        }
        .then(fullscreenStartSubCommand())
        .then(
            Commands.literal("stop")
                .requires { it.sender.hasPermission(PaperServer.config.permissions.fullscreenStop) }
                .then(
                    Commands.argument("id", StringArgumentType.word())
                        .suggests { _, builder ->
                            PaperFullscreenCommand.stopSuggestions().forEach { builder.suggest(it) }
                            builder.buildFuture()
                        }
                        .executes { ctx ->
                            PaperFullscreenCommand.stop(ctx.source.sender, StringArgumentType.getString(ctx, "id"))
                            Command.SINGLE_SUCCESS
                        }
                )
        )
        .then(
            Commands.literal("list")
                .requires { it.sender.hasPermission(PaperServer.config.permissions.fullscreenList) }
                .executes { ctx ->
                    PaperFullscreenCommand.list(ctx.source.sender)
                    Command.SINGLE_SUCCESS
                }
        )

    /** Builds the fullscreen start subcommand with optional flags in any order. */
    private fun fullscreenStartSubCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        val flags = fullscreenFlagsNode()
        return Commands.literal("start")
            .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.fullscreenStart) }
            .then(fullscreenIdOrUrlNode("id", flags))
            .then(fullscreenIdOrUrlNode("url", flags))
    }

    /** Builds the `id <id>` / `url <url>` branch under `/display fullscreen start`, both feeding the same `id` argument. */
    private fun fullscreenIdOrUrlNode(
        literalName: String,
        flags: List<CommandNode<CommandSourceStack>>,
    ) = Commands.literal(literalName).then(
        Commands.argument("id", PaperBareTokenArgumentType)
            .suggests { _, builder ->
                if (literalName == "id") {
                    builder.suggest("this")
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { builder.suggest(it) }
                }
                builder.buildFuture()
            }
            .executes { ctx -> runFullscreenStart(ctx); Command.SINGLE_SUCCESS }
            .also { idArg -> flags.forEach { idArg.then(it) } }
    )

    /** Bare space-delimited token argument type with custom character validation. */
    private object PaperBareTokenArgumentType : CustomArgumentType<String, String> {
        private val MISSING = SimpleCommandExceptionType(LiteralMessage("Expected a value."))

        override fun parse(reader: StringReader): String {
            val start = reader.cursor
            while (reader.canRead() && reader.peek() != ' ') reader.skip()
            if (reader.cursor == start) throw MISSING.create()
            return reader.string.substring(start, reader.cursor)
        }

        override fun getNativeType(): ArgumentType<String> = StringArgumentType.greedyString()
    }

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
        node.executes { ctx -> runFullscreenStart(ctx); Command.SINGLE_SUCCESS }
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
                .requires { it.sender.hasPermission(PaperServer.config.permissions.fullscreenNetwork) }
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
                    Commands.argument("players", PaperBareTokenArgumentType)
                        .suggests { _, builder -> suggestPlayerNames(builder) },
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

    /** Gathers every flag argument present on the parsed [ctx] path and delegates to [PaperFullscreenCommand.start]. */
    private fun runFullscreenStart(ctx: CommandContext<CommandSourceStack>) {
        val nodeNames = ctx.nodes.map { it.node.name }
        val mode = when {
            "standard" in nodeNames -> "standard"
            "immersive" in nodeNames -> "immersive"
            else -> null
        }
        PaperFullscreenCommand.start(
            ctx.source.sender,
            id = StringArgumentType.getString(ctx, "id"),
            serverScope = tryArg(ctx, "name", String::class.java),
            players = tryArg(ctx, "players", String::class.java),
            radiusBlocks = tryArg(ctx, "blocks", Double::class.javaObjectType)?.toDouble(),
            radiusX = tryArg(ctx, "x", Double::class.javaObjectType)?.toDouble(),
            radiusY = tryArg(ctx, "y", Double::class.javaObjectType)?.toDouble(),
            radiusZ = tryArg(ctx, "z", Double::class.javaObjectType)?.toDouble(),
            mode = mode,
            forced = "forced" in nodeNames,
            transientSession = "transient" in nodeNames,
            volume = tryArg(ctx, "volume", Double::class.javaObjectType)?.let { (it.toFloat() / 200f) },
            loop = "looped" in nodeNames,
            quality = tryArg(ctx, "quality", String::class.java),
        )
    }

    /** Suggests online player names for the last comma-separated fragment of the `players` argument. */
    private fun suggestPlayerNames(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val remaining = builder.remaining
        val prefix = remaining.substringAfterLast(',')
        val before = remaining.substringBeforeLast(',', "").let { if (it.isEmpty()) "" else "$it," }
        (TARGET_SELECTORS + PaperFullscreenCommand.onlinePlayerNames())
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .forEach { builder.suggest(before + it) }
        return builder.buildFuture()
    }

    /** Builds the `/display list [filter] [value] [page]` subcommand with progressive suggestions. */
    private fun listSubCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        val cmd = ListCommand()
        return Commands.literal("list")
            .requires { it.sender.hasPermission(PaperServer.config.permissions.list) }
            .executes { ctx ->
                cmd.execute(ctx.source.sender, arrayOf("list"))
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("filter", StringArgumentType.word())
                    .suggests { ctx, builder ->
                        cmd.complete(ctx.source.sender, arrayOf("list", builder.remaining))
                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                            .forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val filter = StringArgumentType.getString(ctx, "filter")
                        cmd.execute(ctx.source.sender, arrayOf("list", filter))
                        Command.SINGLE_SUCCESS
                    }
                    .then(
                        Commands.argument("value", StringArgumentType.word())
                            .suggests { ctx, builder ->
                                val filter = StringArgumentType.getString(ctx, "filter")
                                cmd.complete(ctx.source.sender, arrayOf("list", filter, builder.remaining))
                                    .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                                    .forEach { builder.suggest(it) }
                                builder.buildFuture()
                            }
                            .executes { ctx ->
                                val filter = StringArgumentType.getString(ctx, "filter")
                                val value = StringArgumentType.getString(ctx, "value")
                                cmd.execute(ctx.source.sender, arrayOf("list", filter, value))
                                Command.SINGLE_SUCCESS
                            }
                            .then(
                                Commands.argument("page", StringArgumentType.word())
                                    .suggests { ctx, builder ->
                                        val filter = StringArgumentType.getString(ctx, "filter")
                                        val value = StringArgumentType.getString(ctx, "value")
                                        cmd.complete(
                                            ctx.source.sender,
                                            arrayOf("list", filter, value, builder.remaining)
                                        )
                                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                                            .forEach { builder.suggest(it) }
                                        builder.buildFuture()
                                    }
                                    .executes { ctx ->
                                        val filter = StringArgumentType.getString(ctx, "filter")
                                        val value = StringArgumentType.getString(ctx, "value")
                                        val page = StringArgumentType.getString(ctx, "page")
                                        cmd.execute(ctx.source.sender, arrayOf("list", filter, value, page))
                                        Command.SINGLE_SUCCESS
                                    }
                            )
                    )
            )
    }
}
