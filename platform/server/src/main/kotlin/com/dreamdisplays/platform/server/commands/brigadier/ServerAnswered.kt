package com.dreamdisplays.platform.server.commands.brigadier

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.suggestion.Suggestion
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import java.util.concurrent.CompletableFuture
import kotlin.math.min

/** Wraps this tree so the client only ever sees `/<name> <command>` and asks the server for every completion. */
fun <S> LiteralCommandNode<S>.answeredByServer(): LiteralCommandNode<S> {
    val tree = CommandDispatcher<S>()
    children.forEach { tree.root.addChild(it) }

    val root = LiteralArgumentBuilder.literal<S>(literal).requires(requirement)
    command?.let { root.executes(it) }
    root.then(
        RequiredArgumentBuilder.argument<S, String>("command", StringArgumentType.greedyString())
            .suggests { ctx, builder -> tree.complete(ctx.source, builder) }
            .executes { ctx -> tree.execute(readerAt(ctx.input, ctx.nodes.last().range.start), ctx.source) }
    )
    return root.build()
}

private fun readerAt(input: String, cursor: Int): StringReader = StringReader(input).also { it.cursor = cursor }

private fun <S> CommandDispatcher<S>.complete(source: S, outer: SuggestionsBuilder): CompletableFuture<Suggestions> {
    val input = outer.input
    val parse = parse(readerAt(input, outer.start), source)
    val at = runCatching { parse.context.findSuggestionContext(input.length) }.getOrNull()
        ?: return Suggestions.empty()
    val start = min(at.startPos, input.length)
    val context = parse.context.build(input)

    val futures = at.parent.children.filter { it.canUse(source) }.map { node ->
        try {
            node.listSuggestions(context, SuggestionsBuilder(input, input.lowercase(), start))
        } catch (_: CommandSyntaxException) {
            Suggestions.empty()
        }
    }
    return CompletableFuture.allOf(*futures.toTypedArray()).thenApply { inOrder(input, futures.map { it.join() }) }
}

private fun inOrder(input: String, parts: List<Suggestions>): Suggestions {
    val seen = HashSet<String>()
    val entries = parts.flatMap { it.list }.filter { seen.add(it.text) }
    if (entries.isEmpty()) return Suggestions(StringRange.at(input.length), emptyList())
    val range = entries.map(Suggestion::getRange).reduce(StringRange::encompassing)
    return Suggestions(range, entries.map { it.expand(input, range) })
}
