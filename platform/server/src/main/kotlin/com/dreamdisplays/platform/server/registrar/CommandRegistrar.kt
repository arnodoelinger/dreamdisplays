package com.dreamdisplays.platform.server.registrar

import io.github.arnodoelinger.platformweaver.PaperOnly

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.commands.subcommands.*
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Command registrar. Uses Brigadier to build the `/display` command tree. See
 * `VanillaCommandTree.kt` for the shared `Fabric` / `NeoForge` equivalent.
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
