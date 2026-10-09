package com.dreamdisplays.platform.server.commands.brigadier

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext

/** Creates a root literal command node builder. [tooltip] gives the text shown beside the name to a source. */
fun <S> literal(
    name: String,
    tooltip: ((S) -> String)? = null,
    block: LiteralArgumentBuilder<S>.() -> Unit = {},
): LiteralArgumentBuilder<S> = TooltipLiteralBuilder<S>(name, tooltip).apply(block)

/** Adds a child literal to this literal node. [tooltip] gives the text shown beside the name to a source. */
fun <S> LiteralArgumentBuilder<S>.literal(
    name: String,
    tooltip: ((S) -> String)? = null,
    block: LiteralArgumentBuilder<S>.() -> Unit = {},
): LiteralArgumentBuilder<S> {
    val child = TooltipLiteralBuilder<S>(name, tooltip).apply(block)
    then(child)
    return child
}

/** Adds a child literal to this argument node. [tooltip] gives the text shown beside the name to a source. */
fun <S, T> RequiredArgumentBuilder<S, T>.literal(
    name: String,
    tooltip: ((S) -> String)? = null,
    block: LiteralArgumentBuilder<S>.() -> Unit = {},
): LiteralArgumentBuilder<S> {
    val child = TooltipLiteralBuilder<S>(name, tooltip).apply(block)
    then(child)
    return child
}

/** Adds a child argument to this literal node. */
fun <S, T : Any> LiteralArgumentBuilder<S>.argument(
    name: String,
    type: ArgumentType<T>,
    block: RequiredArgumentBuilder<S, T>.() -> Unit = {},
): RequiredArgumentBuilder<S, T> {
    val child = RequiredArgumentBuilder.argument<S, T>(name, type).apply(block)
    then(child)
    return child
}

/** Adds a child argument to this argument node. */
fun <S, T, U : Any> RequiredArgumentBuilder<S, T>.argument(
    name: String,
    type: ArgumentType<U>,
    block: RequiredArgumentBuilder<S, U>.() -> Unit = {},
): RequiredArgumentBuilder<S, U> {
    val child = RequiredArgumentBuilder.argument<S, U>(name, type).apply(block)
    then(child)
    return child
}

/** Sets the execution handler, automatically returning [Command.SINGLE_SUCCESS]. */
fun <S> ArgumentBuilder<S, *>.executesCommand(handler: (CommandContext<S>) -> Unit) {
    executes { ctx ->
        handler(ctx)
        Command.SINGLE_SUCCESS
    }
}
