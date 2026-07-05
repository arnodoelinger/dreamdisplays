package com.dreamdisplays.platform.server.registrar

import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.commands.subcommands.*
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import java.util.Locale

/**
 * Shared `Fabric` / `NeoForge` `/display` command tree. Vanilla Brigadier types
 * (`net.minecraft.commands.Commands`/`CommandSourceStack`) are used fully-qualified throughout
 * since `CommandRegistrar` (see `CommandRegistrar.kt`) already imports Paper's same-named
 * Brigadier wrapper types.
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
        .requires { requiresNode(it, { p -> p.create }, VanillaPermissions.Fallback.EVERYONE) }
        .executes { ctx ->
            VanillaCreateCommand.execute(ctx)
        }

    /** Builds the `/display delete` subcommand. */
    private fun deleteNode() = net.minecraft.commands.Commands.literal("delete")
        .executes { ctx ->
            VanillaDeleteCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

    /** Builds the `/display info` subcommand. */
    private fun infoNode() = net.minecraft.commands.Commands.literal("info")
        .requires { requiresNode(it, { p -> p.info }, VanillaPermissions.Fallback.EVERYONE) }
        .executes { ctx ->
            VanillaInfoCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

    /** Builds the `/display stats` subcommand. */
    private fun statsNode() = net.minecraft.commands.Commands.literal("stats")
        .requires { requiresNode(it, { p -> p.stats }, VanillaPermissions.Fallback.OP) }
        .executes { ctx ->
            VanillaStatsCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

    /** Builds the `/display reload` subcommand. */
    private fun reloadNode() = net.minecraft.commands.Commands.literal("reload")
        .requires { requiresNode(it, { p -> p.reload }, VanillaPermissions.Fallback.OP) }
        .executes { ctx ->
            VanillaReloadCommand.execute(ctx)
            Command.SINGLE_SUCCESS
        }

    /** Builds the `/display video <url> [lang]` subcommand. */
    private fun videoNode() = net.minecraft.commands.Commands.literal("video")
        .requires { requiresNode(it, { p -> p.video }, VanillaPermissions.Fallback.EVERYONE) }
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
            .requires { requiresNode(it, { p -> p.list }, VanillaPermissions.Fallback.OP) }
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

    /** Permission gate shared by every node: console always passes, players are checked against [node]. */
    private fun requiresNode(
        source: net.minecraft.commands.CommandSourceStack,
        node: (com.dreamdisplays.platform.server.VanillaConfig.PermissionsSection) -> String,
        fallback: VanillaPermissions.Fallback,
    ): Boolean {
        val player = source.entity as? net.minecraft.server.level.ServerPlayer
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
