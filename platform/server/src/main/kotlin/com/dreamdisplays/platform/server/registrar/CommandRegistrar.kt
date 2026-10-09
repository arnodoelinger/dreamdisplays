package com.dreamdisplays.platform.server.registrar

import com.dreamdisplays.platform.server.PaperServer
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
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ScheduleTimeUtil
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.arnodoelinger.platformweaver.PaperOnly
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player

/**
 * Command registrar. Builds the `/display` command tree. See `VanillaCommandTree.kt` for the shared
 * `Fabric` / `NeoForge` equivalent, built on the same DSL and suggestions.
 */
@PaperOnly
object CommandRegistrar {
    /** Builds the full tree for the `/display` command with all subcommands. */
    fun buildDisplayCommand(): LiteralCommandNode<CommandSourceStack> =
        literal<CommandSourceStack>("display") {
            executesCommand { ctx -> HelpCommand().execute(ctx.source.sender, emptyArray()) }

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
            toggleBranch("on", "suggestOn", OnCommand())
            toggleBranch("off", "suggestOff", OffCommand())
            helpBranch()
        }.build().answeredByServer()

    private val tips = Tooltips<CommandSourceStack> { source, key, args ->
        MessageUtil.formatIndexed(source.sender, key, *args)
    }

    private fun tip(key: String): (CommandSourceStack) -> String = { tips.of(it, key) }

    private fun LiteralArgumentBuilder<CommandSourceStack>.helpBranch() {
        literal("help", tip("suggestHelp")) {
            executesCommand { ctx -> HelpCommand().execute(ctx.source.sender, emptyArray()) }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.createBranch() {
        literal("create", tip("suggestCreate")) {
            requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.create) }
            executesCommand { ctx -> CreateCommand().execute(ctx.source.sender, emptyArray()) }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.deleteBranch() {
        literal("delete", tip("suggestDelete")) {
            requires { it.sender is Player }
            target {
                executesCommand { ctx -> DeleteCommand().execute(ctx.source.sender, arrayOf(target(ctx))) }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.infoBranch() {
        literal("info", tip("suggestInfo")) {
            requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.info) }
            target {
                executesCommand { ctx -> InfoCommand().execute(ctx.source.sender, arrayOf(target(ctx))) }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.statsBranch() {
        literal("stats", tip("suggestStats")) {
            requires { it.sender.hasPermission(PaperServer.config.permissions.stats) }
            executesCommand { ctx -> StatsCommand().execute(ctx.source.sender, emptyArray()) }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.reloadBranch() {
        literal("reload", tip("suggestReload")) {
            requires { it.sender.hasPermission(PaperServer.config.permissions.reload) }
            executesCommand { ctx -> ReloadCommand().execute(ctx.source.sender, emptyArray()) }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.videoBranch() {
        literal("video", tip("suggestVideo")) {
            requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.video) }
            target {
                argument("url_and_lang", StringArgumentType.greedyString()) {
                    suggests(suggesting(tips) { _, remaining, typed ->
                        DisplaySuggestions.link(remaining, typed) { VideoCommand.languageSuggestions }
                    })
                    executesCommand { ctx ->
                        val parts = StringArgumentType.getString(ctx, "url_and_lang").trim().split(" ")
                        val lang = if (parts.size > 1) parts.last() else ""
                        VideoCommand().execute(ctx.source.sender, arrayOf(target(ctx), parts[0], lang))
                    }
                }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.nameBranch() {
        literal("name", tip("suggestName")) {
            requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.name) }
            target {
                executesCommand { ctx -> NameCommand().execute(ctx.source.sender, arrayOf(target(ctx))) }
                argument("name", StringArgumentType.word()) {
                    suggests(suggesting(tips) { ctx, _, typed -> DisplaySuggestions.currentName(target(ctx), typed) })
                    executesCommand { ctx ->
                        NameCommand().execute(
                            ctx.source.sender,
                            arrayOf(target(ctx), StringArgumentType.getString(ctx, "name")),
                        )
                    }
                }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.scheduleBranch() {
        literal("schedule", tip("suggestSchedule")) {
            requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.schedule) }
            target {
                executesCommand { ctx -> schedule(ctx, null, null) }
                scheduleAction("play", "suggestSchedulePlay")
                scheduleAction("pause", "suggestSchedulePause")
                literal("cancel", tip("suggestScheduleCancel")) {
                    executesCommand { ctx -> schedule(ctx, "cancel", null) }
                }
            }
        }
    }

    private fun RequiredArgumentBuilder<CommandSourceStack, String>.scheduleAction(action: String, tooltip: String) {
        literal(action, tip(tooltip)) {
            executesCommand { ctx -> schedule(ctx, action, null) }
            argument("time", BareTokenArgumentType) {
                suggests(suggesting(tips) { ctx, _, typed ->
                    val player = ctx.source.sender as? Player
                    val offset = player?.let { ScheduleTimeUtil.offsetMinutesOf(it.uniqueId) } ?: 0
                    DisplaySuggestions.scheduleTimes(offset, typed)
                })
                executesCommand { ctx -> schedule(ctx, action, StringArgumentType.getString(ctx, "time")) }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.listBranch() {
        val cmd = ListCommand()
        literal("list", tip("suggestList")) {
            requires { it.sender.hasPermission(PaperServer.config.permissions.list) }
            executesCommand { ctx -> cmd.execute(ctx.source.sender, arrayOf("list")) }
            argument("filter", StringArgumentType.word()) {
                suggests(suggesting(tips) { _, _, typed -> DisplaySuggestions.listFilters(typed) })
                executesCommand { ctx -> cmd.execute(ctx.source.sender, arrayOf("list", filter(ctx))) }
                argument("value", StringArgumentType.word()) {
                    suggests(suggesting(tips) { ctx, _, typed ->
                        completions(cmd, ctx, arrayOf("list", filter(ctx), typed)).startingWith(typed)
                    })
                    executesCommand { ctx ->
                        cmd.execute(ctx.source.sender, arrayOf("list", filter(ctx), value(ctx)))
                    }
                    argument("page", StringArgumentType.word()) {
                        suggests(suggesting(tips) { ctx, _, typed ->
                            completions(cmd, ctx, arrayOf("list", filter(ctx), value(ctx), typed)).startingWith(typed)
                        })
                        executesCommand { ctx ->
                            cmd.execute(
                                ctx.source.sender,
                                arrayOf("list", filter(ctx), value(ctx), StringArgumentType.getString(ctx, "page")),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.toggleBranch(name: String, tooltip: String, cmd: SubCommand) {
        literal(name, tip(tooltip)) {
            executesCommand { ctx -> cmd.execute(ctx.source.sender, arrayOf(name)) }
            argument("player", StringArgumentType.word()) {
                requires { it.sender.hasPermission(PaperServer.config.permissions.toggleOthers) }
                suggests(suggesting(tips) { _, _, typed ->
                    DisplaySuggestions.players(PaperFullscreenCommand.onlinePlayerNames(), typed)
                })
                executesCommand { ctx ->
                    cmd.execute(ctx.source.sender, arrayOf(name, StringArgumentType.getString(ctx, "player")))
                }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.fullscreenBranch() {
        val flags = FullscreenFlags<CommandSourceStack>(
            run = { ctx -> runFullscreenStart(ctx); Command.SINGLE_SUCCESS },
            network = { it.sender.hasPermission(PaperServer.config.permissions.fullscreenNetwork) },
            players = { PaperFullscreenCommand.onlinePlayerNames() },
            position = { source -> source.location.let { Triple(it.x, it.y, it.z) } },
            tooltips = tips,
        ).nodes()

        literal("fullscreen", tip("suggestFullscreen")) {
            executesCommand { ctx ->
                (ctx.source.sender as? Player)?.let { player ->
                    MessageUtil.sendColoredMessage(
                        ctx.source.sender,
                        "&f ${PaperServer.config.getMessageForPlayer(player, "displayHelpFullscreen")}"
                    )
                }
            }
            literal("start", tip("suggestFullscreenStart")) {
                requires { it.sender.hasPermission(PaperServer.config.permissions.fullscreenStart) }
                literal("id", tip("suggestFullscreenId")) {
                    fullscreenSource(flags) { ctx, typed -> DisplaySuggestions.targets(typed, remote(ctx.source)) }
                }
                literal("url", tip("suggestFullscreenUrl")) {
                    fullscreenSource(flags) { _, typed -> DisplaySuggestions.links(typed) }
                }
            }
            literal("stop", tip("suggestFullscreenStop")) {
                requires { it.sender.hasPermission(PaperServer.config.permissions.fullscreenStop) }
                argument("id", StringArgumentType.word()) {
                    suggests(suggesting(tips) { _, _, typed -> DisplaySuggestions.fullscreenStops(typed) })
                    executesCommand { ctx ->
                        PaperFullscreenCommand.stop(ctx.source.sender, StringArgumentType.getString(ctx, "id"))
                    }
                }
            }
            literal("list", tip("suggestFullscreenList")) {
                requires { it.sender.hasPermission(PaperServer.config.permissions.fullscreenList) }
                executesCommand { ctx -> PaperFullscreenCommand.list(ctx.source.sender) }
            }
        }
    }

    private fun LiteralArgumentBuilder<CommandSourceStack>.fullscreenSource(
        flags: List<CommandNode<CommandSourceStack>>,
        items: (CommandContext<CommandSourceStack>, String) -> List<Suggestion>,
    ) {
        argument("id", BareTokenArgumentType) {
            suggests(suggesting(tips) { ctx, _, typed -> items(ctx, typed) })
            executesCommand { ctx -> runFullscreenStart(ctx) }
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

    private fun schedule(ctx: CommandContext<CommandSourceStack>, action: String?, time: String?) {
        ScheduleCommand().execute(ctx.source.sender, arrayOf(target(ctx), action, time))
    }

    private fun completions(
        cmd: SubCommand,
        ctx: CommandContext<CommandSourceStack>,
        args: Array<String?>,
    ): List<Suggestion> = cmd.complete(ctx.source.sender, args).map { Suggestion(it) }

    private fun remote(source: CommandSourceStack): Boolean =
        source.sender.hasPermission(PaperServer.config.permissions.remote)

    private fun <T : Any> tryArg(ctx: CommandContext<CommandSourceStack>, name: String, type: Class<T>): T? =
        runCatching { ctx.getArgument(name, type) }.getOrNull()

    private fun runFullscreenStart(ctx: CommandContext<CommandSourceStack>) {
        val nodeNames = ctx.nodes.map { it.node.name }
        val mode = when {
            "standard" in nodeNames -> "standard"
            "immersive" in nodeNames -> "immersive"
            else -> null
        }
        PaperFullscreenCommand.start(
            ctx.source.sender,
            origin = ctx.source.location,
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
}
