package com.dreamdisplays.platform.server.registrar

import com.dreamdisplays.platform.server.VanillaConfig
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.commands.subcommands.*
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import net.minecraft.commands.Commands
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * Shared `Fabric` / `NeoForge` `/display` command tree. See `CommandRegistrar.kt` for the
 * `Paper` equivalent (built on `io.papermc.paper.command.brigadier` wrapper types of the same
 * simple names; imports are per-file in Kotlin so the two coexist without collision).
 */
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

    /** Builds the `/display delete` subcommand. */
    private fun deleteNode() = Commands.literal("delete")
        .executes { ctx ->
            VanillaDeleteCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

    /** Builds the `/display info` subcommand. */
    private fun infoNode() = Commands.literal("info")
        .requires { requiresNode(it, { p -> p.info }, VanillaPermissions.Fallback.EVERYONE) }
        .executes { ctx ->
            VanillaInfoCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

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

    /** Builds the `/display video <url> [lang]` subcommand. */
    private fun videoNode() = Commands.literal("video")
        .requires { requiresNode(it, { p -> p.video }, VanillaPermissions.Fallback.EVERYONE) }
        .then(
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
                    VanillaVideoCommand.execute(ctx, urlAndLang)
                    Command.SINGLE_SUCCESS
                }
        )

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
            MessageUtil.sendColoredMessage(player, VanillaServerState.config.getMessageForPlayer(player, "displayHelpFullscreen"))
            Command.SINGLE_SUCCESS
        }
        .then(fullscreenStartNode())
        .then(fullscreenStopNode())
        .then(fullscreenListNode())

    /**
     * `/display fullscreen start <id|url> [<flags in any order/combination>]`, flags being
     * `target <players>`, `radius <blocks> [<x> <y> <z>]`, `mode <standard|immersive>`, `forced`,
     * `transient`, `volume <0–200>` - every flag is a real literal/argument node with its own
     * tab-complete, and [fullscreenFlagsNode] lets them appear in any order and any subset.
     */
    private fun fullscreenStartNode() = Commands.literal("start")
        .requires { requiresNode(it, { p -> p.fullscreenStart }, VanillaPermissions.Fallback.OP) }
        .then(
            Commands.argument("id", BareTokenArgumentType)
                .suggests { _, builder ->
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { builder.suggest(it) }
                    builder.buildFuture()
                }
                .executes { ctx -> runFullscreenStart(ctx) }
                .also { idArg -> fullscreenFlagsNode().forEach { idArg.then(it) } }
        )

    /**
     * All fullscreen-start flags, each combinable with every other flag remaining in [names], in
     * any order.
     *
     * Warning: built bottom-up and memoized per remaining-flag-set: naively rebuilding every
     * flag's whole subtree at every recursion step (one call per *permutation* of the 8 flags)
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
        node.executes { ctx -> runFullscreenStart(ctx) }
        children.forEach { node.then(it) }
        return node.build()
    }

    /** Builds one flag's own literal / argument subtree, attaching the already-built [children] at every terminal. */
    private fun buildFullscreenFlagNode(name: String, children: List<CommandNode<CommandSourceStack>>): CommandNode<CommandSourceStack> =
        when (name) {
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
        node: (VanillaConfig.PermissionsSection) -> String,
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
