package com.dreamdisplays.platform.server.registrar

import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import io.github.arnodoelinger.platformweaver.PaperOnly

import com.dreamdisplays.platform.server.Main
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.commands.subcommands.*
import com.dreamdisplays.platform.server.utils.net.VanillaServerPacketHandler
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.neoforged.neoforge.event.RegisterCommandsEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.Locale

/**
 * Command registrar. Uses Brigadier to build the `/display` command tree.
 */
@PaperOnly
object CommandRegistrar {
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
            ) { it.sender is Player && it.sender.hasPermission(Main.config.permissions.create) })
        .then(
            simple(
                "delete",
                DeleteCommand()
            ) { it.sender is Player && it.sender.hasPermission(Main.config.permissions.delete) })
        .then(
            simple(
                "info",
                InfoCommand()
            ) { it.sender is Player && it.sender.hasPermission(Main.config.permissions.info) })
        .then(simple("stats", StatsCommand()) { it.sender.hasPermission(Main.config.permissions.stats) })
        .then(simple("reload", ReloadCommand()) { it.sender.hasPermission(Main.config.permissions.reload) })
        .then(videoSubCommand())
        .then(listSubCommand())
        .then(toggleSubCommand("on", OnCommand()))
        .then(toggleSubCommand("off", OffCommand()))
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
        .requires { it.sender is Player && it.sender.hasPermission(Main.config.permissions.video) }
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
                .requires { it.sender.hasPermission(Main.config.permissions.toggleOthers) }
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

    /** Builds the `/display list [filter] [value] [page]` subcommand with progressive suggestions. */
    private fun listSubCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        val cmd = ListCommand()
        return Commands.literal("list")
            .requires { it.sender.hasPermission(Main.config.permissions.list) }
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

/**
 * Shared `Fabric` / `NeoForge` `/display` command tree. Vanilla Brigadier types
 * (`net.minecraft.commands.Commands`/`CommandSourceStack`) are used fully-qualified throughout
 * since [CommandRegistrar] above already imports Paper's same-named Brigadier wrapper types.
 */
object VanillaCommandTree {
    /** Builds the full `/display` command tree, ready to attach to a dispatcher root. */
    fun build(): com.mojang.brigadier.tree.LiteralCommandNode<net.minecraft.commands.CommandSourceStack> =
        net.minecraft.commands.Commands.literal("display")
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
            .build()

    /** Builds the `/display help` subcommand. */
    private fun helpNode() = net.minecraft.commands.Commands.literal("help")
        .executes { ctx ->
            VanillaHelpCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

    /** Builds the `/display create` subcommand. */
    private fun createNode() = net.minecraft.commands.Commands.literal("create")
        .executes { ctx ->
            VanillaCreateCommand.execute(ctx)
        }

    /** Builds the `/display delete` subcommand. */
    private fun deleteNode() = net.minecraft.commands.Commands.literal("delete")
        .requires(::requiresOpLevel2)
        .executes { ctx ->
            VanillaDeleteCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

    /** Builds the `/display info` subcommand. */
    private fun infoNode() = net.minecraft.commands.Commands.literal("info")
        .executes { ctx ->
            VanillaInfoCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

    /** Builds the `/display stats` subcommand. */
    private fun statsNode() = net.minecraft.commands.Commands.literal("stats")
        .requires(::requiresOpLevel2)
        .executes { ctx ->
            VanillaStatsCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

    /** Builds the `/display reload` subcommand. */
    private fun reloadNode() = net.minecraft.commands.Commands.literal("reload")
        .requires(::requiresOpLevel2)
        .executes { ctx ->
            VanillaReloadCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

    /** Builds the `/display video <url> [lang]` subcommand. */
    private fun videoNode() = net.minecraft.commands.Commands.literal("video")
        .then(
            net.minecraft.commands.Commands.argument("url_and_lang", StringArgumentType.greedyString())
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
    private fun listNode(): LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> {
        return net.minecraft.commands.Commands.literal("list")
            .requires(::requiresOpLevel2)
            .executes { ctx ->
                VanillaListCommand.execute(ctx)
                Command.SINGLE_SUCCESS
            }
            .then(
                net.minecraft.commands.Commands.argument("filter", StringArgumentType.word())
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
                        net.minecraft.commands.Commands.argument("value", StringArgumentType.word())
                            .executes { ctx ->
                                val filter = StringArgumentType.getString(ctx, "filter")
                                val value = StringArgumentType.getString(ctx, "value")
                                VanillaListCommand.execute(ctx, filter = filter, value = value)
                                Command.SINGLE_SUCCESS
                            }
                            .then(
                                net.minecraft.commands.Commands.argument("page", StringArgumentType.word())
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
    private fun toggleNode(name: String) = net.minecraft.commands.Commands.literal(name)
        .executes { ctx ->
            when (name) {
                "on" -> VanillaOnCommand.execute(ctx)
                "off" -> VanillaOffCommand.execute(ctx)
            }
            Command.SINGLE_SUCCESS
        }
        .then(
            net.minecraft.commands.Commands.argument("player", StringArgumentType.word())
                .requires(::requiresOpLevel2)
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

    /** Permission gate shared by every admin-only node: console always passes, players need op level 2. */
    private fun requiresOpLevel2(source: net.minecraft.commands.CommandSourceStack): Boolean {
        val player = source.entity as? net.minecraft.server.level.ServerPlayer
        return player == null || VanillaServerPacketHandler.isOpLevel2(player)
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

/** `Fabric` event-registration adapter for [VanillaCommandTree]. */
@FabricOnly
object FabricCommandRegistrar {
    /** Registers the `/display` command tree with `Fabric`'s command dispatcher. */
    fun register() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.root.addChild(VanillaCommandTree.build())
        }
    }
}

/**
 * `NeoForge` event-registration adapter for [VanillaCommandTree]. Registered on
 * `NeoForge.EVENT_BUS` from `NeoForgeServer` rather than `Fabric`'s `CommandRegistrationCallback`.
 */
@NeoForgeOnly
object NeoForgeCommandRegistrar {
    /** Registers the `/display` command tree against a `RegisterCommandsEvent`. */
    fun register(event: RegisterCommandsEvent) {
        event.dispatcher.root.addChild(VanillaCommandTree.build())
    }
}
