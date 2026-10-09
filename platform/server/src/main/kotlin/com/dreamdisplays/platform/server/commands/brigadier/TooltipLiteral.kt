package com.dreamdisplays.platform.server.commands.brigadier

import com.mojang.brigadier.Command
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.RedirectModifier
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate

/** Builds a [TooltipLiteralNode]. */
internal class TooltipLiteralBuilder<S>(
    private val literalName: String,
    private val tooltip: ((S) -> String)?,
) : LiteralArgumentBuilder<S>(literalName) {
    override fun getThis(): LiteralArgumentBuilder<S> = this

    override fun build(): LiteralCommandNode<S> {
        val result = TooltipLiteralNode(
            literalName,
            tooltip,
            command,
            requirement,
            redirect,
            redirectModifier,
            isFork,
        )
        for (child in arguments) result.addChild(child)
        return result
    }
}

/** Literal that is only suggested to sources allowed to use it, with what [tooltip] gives shown beside its name. */
internal class TooltipLiteralNode<S>(
    private val literalName: String,
    private val tooltip: ((S) -> String)?,
    command: Command<S>?,
    requirement: Predicate<S>,
    redirect: CommandNode<S>?,
    modifier: RedirectModifier<S>?,
    forks: Boolean,
) : LiteralCommandNode<S>(literalName, command, requirement, redirect, modifier, forks) {
    override fun listSuggestions(
        context: CommandContext<S>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        if (!canUse(context.source)) return Suggestions.empty()
        if (!literalName.lowercase().startsWith(builder.remainingLowerCase)) return Suggestions.empty()
        val text = tooltip?.invoke(context.source) ?: return builder.suggest(literalName).buildFuture()
        return builder.suggest(literalName, LiteralMessage(text)).buildFuture()
    }

    override fun createBuilder(): LiteralArgumentBuilder<S> {
        val copy = TooltipLiteralBuilder<S>(literalName, tooltip)
        copy.requires(requirement)
        command?.let { copy.executes(it) }
        redirect?.let { copy.forward(it, redirectModifier, isFork) }
        return copy
    }
}
