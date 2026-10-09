package com.dreamdisplays.platform.server.registrar

import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.platform.server.PermissionsSection
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.commands.brigadier.answeredByServer
import com.dreamdisplays.platform.server.commands.brigadier.argument
import com.dreamdisplays.platform.server.commands.brigadier.executesCommand
import com.dreamdisplays.platform.server.commands.brigadier.literal
import com.dreamdisplays.platform.server.commands.subcommands.*
import com.dreamdisplays.platform.server.commands.suggest.DisplaySuggestions
import com.dreamdisplays.platform.server.commands.suggest.Suggestion
import com.dreamdisplays.platform.server.commands.suggest.Tooltips
import com.dreamdisplays.platform.server.commands.suggest.startingWith
import com.dreamdisplays.platform.server.commands.suggest.suggesting
import com.dreamdisplays.platform.server.commands.tree.FullscreenFlags
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ScheduleTimeUtil
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import java.util.*

/**
 * Shared `Fabric` / `NeoForge` `/display` command tree. See `CommandRegistrar.kt` for the
 * `Paper` equivalent, built on the same DSL and suggestions.
 */
@ModLoaderOnly
object VanillaCommandTree {
    /** Builds the full `/display` command tree, ready to attach to a dispatcher root. */
    fun build(): LiteralCommandNode<CommandSourceStack> =
        literal<CommandSourceStack>("display") {
            executesCommand { ctx -> VanillaHelpCommand.execute(ctx) }

            createBranch()
            deleteBranch()
            videoBranch()
            nameBranch()
            infoBranch()
            listBranch()
            scheduleBranch()
            fullscreenBranch()
            statsBranch()
            reloadBranch()
            toggleBranch("on", "suggestOn")
            toggleBranch("off", "suggestOff")
            helpBranch()
        }.build().answeredByServer()

    private val tips = Tooltips<CommandSourceStack> { source, key, args ->
        MessageUtil.formatIndexed(source.entity as? ServerPlayer, key, *args)
    }

    private fun tip(key: String): (CommandSourceStack) -> String = { tips.of(it, key) }

    private fun LiteralArgumentBuilder<CommandSourceStack>.helpBranch() {
        literal("help", tip("suggestHelp")) {
            executesCommand { ctx -> VanillaHelpCommand.execute(ctx) }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.createBranch() {
        literal("create", tip("suggestCreate")) {
            requires { permitted(it, { p -> p.create }, VanillaPermissions.Fallback.EVERYONE) }
            executes { ctx -> VanillaCreateCommand.execute(ctx) }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.deleteBranch() {
        literal("delete", tip("suggestDelete")) {
            target {
                executesCommand { ctx -> VanillaDeleteCommand.execute(ctx, target(ctx)) }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.infoBranch() {
        literal("info", tip("suggestInfo")) {
            requires { permitted(it, { p -> p.info }, VanillaPermissions.Fallback.EVERYONE) }
            target {
                executesCommand { ctx -> VanillaInfoCommand.execute(ctx, target(ctx)) }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.statsBranch() {
        literal("stats", tip("suggestStats")) {
            requires { permitted(it, { p -> p.stats }, VanillaPermissions.Fallback.OP) }
            executesCommand { ctx -> VanillaStatsCommand.execute(ctx) }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.reloadBranch() {
        literal("reload", tip("suggestReload")) {
            requires { permitted(it, { p -> p.reload }, VanillaPermissions.Fallback.OP) }
            executesCommand { ctx -> VanillaReloadCommand.execute(ctx) }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.videoBranch() {
        literal("video", tip("suggestVideo")) {
            requires { permitted(it, { p -> p.video }, VanillaPermissions.Fallback.EVERYONE) }
            target {
                argument("url_and_lang", StringArgumentType.greedyString()) {
                    suggests(suggesting(tips) { _, remaining, typed -> DisplaySuggestions.link(remaining, typed, ::languages) })
                    executesCommand { ctx ->
                        VanillaVideoCommand.execute(ctx, target(ctx), StringArgumentType.getString(ctx, "url_and_lang"))
                    }
                }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.nameBranch() {
        literal("name", tip("suggestName")) {
            requires { permitted(it, { p -> p.name }, VanillaPermissions.Fallback.EVERYONE) }
            target {
                executes { ctx -> VanillaNameCommand.execute(ctx, target(ctx), null) }
                argument("name", StringArgumentType.word()) {
                    suggests(suggesting(tips) { ctx, _, typed -> DisplaySuggestions.currentName(target(ctx), typed) })
                    executes { ctx ->
                        VanillaNameCommand.execute(ctx, target(ctx), StringArgumentType.getString(ctx, "name"))
                    }
                }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.scheduleBranch() {
        literal("schedule", tip("suggestSchedule")) {
            requires { permitted(it, { p -> p.schedule }, VanillaPermissions.Fallback.EVERYONE) }
            target {
                executes { ctx -> VanillaScheduleCommand.execute(ctx, target(ctx), null, null) }
                scheduleAction("play", "suggestSchedulePlay")
                scheduleAction("pause", "suggestSchedulePause")
                literal("cancel", tip("suggestScheduleCancel")) {
                    executes { ctx -> VanillaScheduleCommand.execute(ctx, target(ctx), "cancel", null) }
                }
            }
        }
    }

    private fun RequiredArgumentBuilder<CommandSourceStack, String>.scheduleAction(action: String, tooltip: String) {
        literal(action, tip(tooltip)) {
            executes { ctx -> VanillaScheduleCommand.execute(ctx, target(ctx), action, null) }
            argument("time", BareTokenArgumentType) {
                suggests(suggesting(tips) { ctx, _, typed ->
                    val player = ctx.source.entity as? ServerPlayer
                    val offset = player?.let { ScheduleTimeUtil.offsetMinutesOf(it.uuid) } ?: 0
                    DisplaySuggestions.scheduleTimes(offset, typed)
                })
                executes { ctx ->
                    VanillaScheduleCommand.execute(ctx, target(ctx), action, StringArgumentType.getString(ctx, "time"))
                }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.listBranch() {
        literal("list", tip("suggestList")) {
            requires { permitted(it, { p -> p.list }, VanillaPermissions.Fallback.OP) }
            executesCommand { ctx -> VanillaListCommand.execute(ctx) }
            argument("filter", StringArgumentType.word()) {
                suggests(suggesting(tips) { _, _, typed -> DisplaySuggestions.listFilters(typed) })
                executesCommand { ctx -> VanillaListCommand.execute(ctx, filter = filter(ctx)) }
                argument("value", StringArgumentType.word()) {
                    suggests(suggesting(tips) { ctx, _, typed -> listValues(ctx, filter(ctx), null).startingWith(typed) })
                    executesCommand { ctx ->
                        VanillaListCommand.execute(ctx, filter = filter(ctx), value = value(ctx))
                    }
                    argument("page", StringArgumentType.word()) {
                        suggests(suggesting(tips) { ctx, _, typed ->
                            listValues(ctx, filter(ctx), value(ctx)).startingWith(typed)
                        })
                        executesCommand { ctx ->
                            VanillaListCommand.execute(
                                ctx,
                                filter = filter(ctx),
                                value = value(ctx),
                                pageStr = StringArgumentType.getString(ctx, "page"),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.toggleBranch(name: String, tooltip: String) {
        literal(name, tip(tooltip)) {
            executesCommand { ctx -> toggle(name, ctx, null) }
            argument("player", StringArgumentType.word()) {
                requires { permitted(it, { p -> p.toggleOthers }, VanillaPermissions.Fallback.OP) }
                suggests(suggesting(tips) { ctx, _, typed ->
                    DisplaySuggestions.players(VanillaFullscreenCommand.onlinePlayerNames(ctx), typed)
                })
                executesCommand { ctx -> toggle(name, ctx, StringArgumentType.getString(ctx, "player")) }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.fullscreenBranch() {
        val flags = FullscreenFlags(
            run = ::runFullscreenStart,
            network = { permitted(it, { p -> p.fullscreenNetwork }, VanillaPermissions.Fallback.OP) },
            players = { VanillaFullscreenCommand.onlinePlayerNames(it) },
            position = { source -> source.position.let { Triple(it.x, it.y, it.z) } },
            tooltips = tips,
        ).nodes()

        literal("fullscreen", tip("suggestFullscreen")) {
            executesCommand { ctx ->
                val player = ctx.source.entity as? ServerPlayer
                MessageUtil.sendColoredMessage(
                    player,
                    VanillaServerState.config.getMessageForPlayer(player, "displayHelpFullscreen")
                )
            }
            literal("start", tip("suggestFullscreenStart")) {
                requires { permitted(it, { p -> p.fullscreenStart }, VanillaPermissions.Fallback.OP) }
                literal("id", tip("suggestFullscreenId")) {
                    fullscreenSource(flags) { ctx, typed -> DisplaySuggestions.targets(typed, remote(ctx.source)) }
                }
                literal("url", tip("suggestFullscreenUrl")) {
                    fullscreenSource(flags) { _, typed -> DisplaySuggestions.links(typed) }
                }
            }
            literal("stop", tip("suggestFullscreenStop")) {
                requires { permitted(it, { p -> p.fullscreenStop }, VanillaPermissions.Fallback.OP) }
                argument("id", StringArgumentType.word()) {
                    suggests(suggesting(tips) { _, _, typed -> DisplaySuggestions.fullscreenStops(typed) })
                    executes { ctx -> VanillaFullscreenCommand.stop(ctx, StringArgumentType.getString(ctx, "id")) }
                }
            }
            literal("list", tip("suggestFullscreenList")) {
                requires { permitted(it, { p -> p.fullscreenList }, VanillaPermissions.Fallback.OP) }
                executes { ctx -> VanillaFullscreenCommand.list(ctx) }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.fullscreenSource(
        flags: List<CommandNode<CommandSourceStack>>,
        items: (CommandContext<CommandSourceStack>, String) -> List<Suggestion>,
    ) {
        argument("id", BareTokenArgumentType) {
            suggests(suggesting(tips) { ctx, _, typed -> items(ctx, typed) })
            executes { ctx -> runFullscreenStart(ctx) }
            flags.forEach { then(it) }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.target(
        block: RequiredArgumentBuilder<CommandSourceStack, String>.() -> Unit,
    ) {
        argument("target", BareTokenArgumentType) {
            suggests(suggesting(tips) { ctx, _, typed -> DisplaySuggestions.targets(typed, remote(ctx.source)) })
            block()
        }
    }

    private fun target(ctx: CommandContext<CommandSourceStack>): String = StringArgumentType.getString(ctx, "target")

    private fun filter(ctx: CommandContext<CommandSourceStack>): String = StringArgumentType.getString(ctx, "filter")

    private fun value(ctx: CommandContext<CommandSourceStack>): String = StringArgumentType.getString(ctx, "value")

    private fun toggle(name: String, ctx: CommandContext<CommandSourceStack>, target: String?) {
        when {
            name == "on" && target == null -> VanillaOnCommand.execute(ctx)
            name == "on" && target != null -> VanillaOnCommand.execute(ctx, target)
            target == null -> VanillaOffCommand.execute(ctx)
            else -> VanillaOffCommand.execute(ctx, target)
        }
    }

    private fun listValues(
        ctx: CommandContext<CommandSourceStack>,
        filter: String,
        value: String?,
    ): List<Suggestion> {
        val displays = DisplayManager.getDisplays().filterIsInstance<VanillaDisplayData>()
        val online = ctx.source.server.playerList.players
        return when (ListFilter.fromToken(filter)) {
            ListFilter.MINE -> if (value != null) emptyList() else {
                val player = ctx.source.entity as? ServerPlayer
                DisplaySuggestions.pages(displays.count { player == null || it.ownerId == player.uuid })
            }

            ListFilter.SYNC -> if (value != null) emptyList() else DisplaySuggestions.pages(displays.count { it.isSync })
            ListFilter.WORLD -> if (value != null) {
                DisplaySuggestions.pages(displays.count { it.worldKey.endsWith(value, ignoreCase = true) })
            } else {
                displays.map { it.worldKey.substringAfterLast(':') }.distinct().sorted().map { Suggestion(it, "suggestWorld") }
            }

            ListFilter.OWNER -> if (value != null) {
                val owner = online.find { it.gameProfile.name.equals(value, ignoreCase = true) }?.uuid
                DisplaySuggestions.pages(
                    displays.count { it.ownerId == owner || it.ownerId.toString().equals(value, ignoreCase = true) }
                )
            } else {
                val owners = displays.map { it.ownerId }.toSet()
                online.filter { it.uuid in owners }.map { it.gameProfile.name }.sorted()
                    .map { Suggestion(it, "suggestOwner") }
            }

            null -> emptyList()
        }
    }

    private fun <T : Any> tryArg(ctx: CommandContext<CommandSourceStack>, name: String, type: Class<T>): T? =
        runCatching { ctx.getArgument(name, type) }.getOrNull()

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

    private fun permitted(
        source: CommandSourceStack,
        node: (PermissionsSection) -> String,
        fallback: VanillaPermissions.Fallback,
    ): Boolean {
        val player = source.entity as? ServerPlayer
        return player == null || VanillaPermissions.has(player, node(VanillaServerState.config.permissions), fallback)
    }

    private fun remote(source: CommandSourceStack): Boolean =
        permitted(source, { p -> p.remote }, VanillaPermissions.Fallback.OP)

    private fun languages(): List<String> = LANGUAGES

    private val LANGUAGES: List<String> by lazy {
        val fromJava = Locale.getAvailableLocales()
            .map { it.language.lowercase(Locale.ROOT) }
        val fromConfig = VanillaServerState.config.languages.keys
            .map { it.trim().lowercase(Locale.ROOT).substringBefore('_') }
        (fromJava + fromConfig)
            .filter { it.matches(Regex("^[a-z]{2}$")) }
            .map { if (it == "uk") "ua" else it }
            .distinct()
            .sorted()
    }
}
