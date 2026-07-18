package com.dreamdisplays.platform.server.registrar

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.commands.subcommands.*
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.registrar.CommandRegistrar.fullscreenFlagsNode
import com.dreamdisplays.platform.server.utils.MessageUtil
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
            simple(
                "delete",
                DeleteCommand()
            ) { it.sender is Player })
        .then(
            simple(
                "info",
                InfoCommand()
            ) { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.info) })
        .then(simple("stats", StatsCommand()) { it.sender.hasPermission(PaperServer.config.permissions.stats) })
        .then(simple("reload", ReloadCommand()) { it.sender.hasPermission(PaperServer.config.permissions.reload) })
        .then(videoSubCommand())
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

    /** Builds the `/display video <url> [lang]` subcommand with greedy argument and language suggestions. */
    private fun videoSubCommand() = Commands.literal("video")
        .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.video) }
        .then(
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
                    VideoCommand().execute(ctx.source.sender, arrayOf("video", url, lang))
                    Command.SINGLE_SUCCESS
                }
        )

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

    /**
     * `/display fullscreen start <id <id>|url <url>> [<flags in any order / combination>]`, flags
     * being `target <players>`, `radius <blocks> [<x> <y> <z>]`, `mode <standard|immersive>`,
     * `forced`, `transient`, `volume <0–200>` - every flag is a real literal / argument node with
     * its own tab-complete, and [fullscreenFlagsNode] lets them appear in any order and any subset.
     * `id` / `url` are separate literal branches (rather than one argument that guesses which it got)
     * so both are actually discoverable via tab-complete.
     */
    private fun fullscreenStartSubCommand() = Commands.literal("start")
        .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.fullscreenStart) }
        .then(fullscreenIdOrUrlNode("id"))
        .then(fullscreenIdOrUrlNode("url"))

    /** Builds the `id <id>` / `url <url>` branch under `/display fullscreen start`, both feeding the same `id` argument. */
    private fun fullscreenIdOrUrlNode(literalName: String) = Commands.literal(literalName).then(
        Commands.argument("id", PaperBareTokenArgumentType)
            .suggests { _, builder ->
                if (literalName == "id") FullscreenBroadcastManager.displayIdSuggestions().forEach { builder.suggest(it) }
                builder.buildFuture()
            }
            .executes { ctx -> runFullscreenStart(ctx); Command.SINGLE_SUCCESS }
            .also { idArg -> fullscreenFlagsNode().forEach { idArg.then(it) } }
    )

    /**
     * Bare, space-delimited token wrapped via `Paper`'s `CustomArgumentType` so its real charset
     * (letters / digits / `@` / `%` / `:` / `/` etc, just not a literal space) can differ from any
     * vanilla-registered type, while the client only ever sees [getNativeType] (`greedyString`) —
     * `Paper` substitutes that already-registered native type when building the per-player
     * command-sync packet (`ApiMirrorRootNode.convertFromPureBrigNode`), so this needs no
     * `ArgumentTypeInfos` registration and works for un-modded vanilla clients too.
     */
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
     * All fullscreen-start flags, each combinable with every other flag remaining in [names], in
     * any order.
     *
     * Warning: built bottom-up and memoized per remaining-flag-set: naively rebuilding every
     * flag's whole subtree at every recursion step (one call per permutation of the 8 flags)
     * blew up to ~110,000 `Brigadier` nodes and stalled server startup for minutes; memoizing by
     * the set of remaining flags (order doesn't affect the subtree's shape) collapses that to
     * ~1,000 builds since equal remaining-sets now share one already-built node instance.
     */
    private fun fullscreenFlagsNode(
        names: Set<String> = setOf("target", "radius", "mode", "forced", "transient", "volume", "looped", "quality"),
        cache: MutableMap<Set<String>, List<CommandNode<CommandSourceStack>>> = HashMap(),
    ): List<CommandNode<CommandSourceStack>> = cache.getOrPut(names) {
        names.map { name -> buildFullscreenFlagNode(name, fullscreenFlagsNode(names - name, cache)) }
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
    private fun buildFullscreenFlagNode(name: String, children: List<CommandNode<CommandSourceStack>>): CommandNode<CommandSourceStack> =
        when (name) {
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
            players = tryArg(ctx, "players", String::class.java),
            radiusBlocks = tryArg(ctx, "blocks", java.lang.Double::class.java)?.toDouble(),
            radiusX = tryArg(ctx, "x", java.lang.Double::class.java)?.toDouble(),
            radiusY = tryArg(ctx, "y", java.lang.Double::class.java)?.toDouble(),
            radiusZ = tryArg(ctx, "z", java.lang.Double::class.java)?.toDouble(),
            mode = mode,
            forced = "forced" in nodeNames,
            transientSession = "transient" in nodeNames,
            volume = tryArg(ctx, "volume", java.lang.Double::class.java)?.let { (it.toFloat() / 200f) },
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
