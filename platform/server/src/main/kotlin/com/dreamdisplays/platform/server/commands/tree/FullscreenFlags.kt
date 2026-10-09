package com.dreamdisplays.platform.server.commands.tree

import com.dreamdisplays.platform.server.commands.brigadier.TooltipLiteralBuilder
import com.dreamdisplays.platform.server.commands.suggest.DisplaySuggestions
import com.dreamdisplays.platform.server.commands.suggest.Tooltips
import com.dreamdisplays.platform.server.commands.suggest.suggesting
import com.dreamdisplays.platform.server.registrar.BareTokenArgumentType
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.CommandNode

/**
 * The flags of `/display fullscreen start`, any subset of them accepted but only in [ORDER]:
 * each flag's subtree offers just the flags that follow it.
 */
internal class FullscreenFlags<S>(
    private val run: (CommandContext<S>) -> Int,
    private val network: (S) -> Boolean,
    private val players: (CommandContext<S>) -> List<String>,
    private val position: (S) -> Triple<Double, Double, Double>?,
    private val tooltips: Tooltips<S>,
) {
    /** Every flag node, each already carrying the flags allowed after it. */
    fun nodes(): List<CommandNode<S>> {
        var following = emptyList<CommandNode<S>>()
        for (name in ORDER.asReversed()) {
            following = listOf(flag(name, following)) + following
        }
        return following
    }

    private fun flag(name: String, next: List<CommandNode<S>>): CommandNode<S> = when (name) {
        "server" -> literal(name, "suggestFlagServer")
            .requires { network(it) }
            .then(
                end(argument("name", StringArgumentType.word())
                    .suggests(suggesting(tooltips) { _, _, typed -> DisplaySuggestions.servers(typed) }), next)
            ).build()

        "target" -> literal(name, "suggestFlagTarget").then(
            end(argument("players", BareTokenArgumentType)
                .suggests(suggesting(tooltips, ',') { ctx, _, typed -> DisplaySuggestions.playerList(players(ctx), typed) }), next)
        ).build()

        "radius" -> literal(name, "suggestFlagRadius").then(
            end(argument("blocks", DoubleArgumentType.doubleArg(0.0))
                .suggests(suggesting(tooltips) { _, _, typed -> DisplaySuggestions.radii(typed) }), next).also { blocks ->
                blocks.addChild(
                    coordinate("x") { it.first }.then(
                        coordinate("y") { it.second }.then(end(coordinate("z") { it.third }, next))
                    ).build()
                )
            }
        ).build()

        "mode" -> literal(name, "suggestFlagMode")
            .then(end(literal("standard", "suggestModeStandard"), next))
            .then(end(literal("immersive", "suggestModeImmersive"), next))
            .build()

        "forced" -> end(literal(name, "suggestFlagForced"), next)
        "transient" -> end(literal(name, "suggestFlagTransient"), next)
        "volume" -> literal(name, "suggestFlagVolume").then(
            end(argument("volume", DoubleArgumentType.doubleArg(0.0, 200.0))
                .suggests(suggesting(tooltips) { _, _, typed -> DisplaySuggestions.volumes(typed) }), next)
        ).build()

        "looped" -> end(literal(name, "suggestFlagLooped"), next)
        "quality" -> literal(name, "suggestFlagQuality").then(
            end(argument("quality", StringArgumentType.word())
                .suggests(suggesting(tooltips) { _, _, typed -> DisplaySuggestions.qualities(typed) }), next)
        ).build()

        else -> error("Unknown fullscreen flag: $name")
    }

    private fun end(node: ArgumentBuilder<S, *>, next: List<CommandNode<S>>): CommandNode<S> {
        node.executes { ctx -> run(ctx) }
        next.forEach { node.then(it) }
        return node.build()
    }

    private fun coordinate(axis: String, pick: (Triple<Double, Double, Double>) -> Double) =
        argument(axis, DoubleArgumentType.doubleArg()).suggests(
            suggesting(tooltips) { ctx, _, typed -> DisplaySuggestions.coordinate(position(ctx.source)?.let(pick), axis, typed) }
        )

    private fun literal(name: String, key: String): LiteralArgumentBuilder<S> =
        TooltipLiteralBuilder(name) { source -> tooltips.of(source, key) }

    private fun <T> argument(name: String, type: ArgumentType<T>) =
        RequiredArgumentBuilder.argument<S, T>(name, type)

    private companion object {
        val ORDER = listOf("server", "target", "radius", "mode", "forced", "transient", "volume", "looped", "quality")
    }
}
