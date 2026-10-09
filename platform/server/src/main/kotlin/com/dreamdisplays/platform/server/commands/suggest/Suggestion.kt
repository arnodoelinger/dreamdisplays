package com.dreamdisplays.platform.server.commands.suggest

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import java.util.concurrent.CompletableFuture
import com.mojang.brigadier.suggestion.Suggestion as BrigadierSuggestion

internal data class Suggestion(val text: String, val tooltip: String? = null, val args: List<String> = emptyList())

internal fun interface Tooltips<S> {
    fun of(source: S, key: String, vararg args: String): String
}

internal fun SuggestionsBuilder.typed(separator: Char = ' '): String = remaining.substringAfterLast(separator)

internal fun SuggestionsBuilder.reply(
    suggestions: List<Suggestion>,
    separator: Char = ' ',
    tooltip: (Suggestion) -> String?,
): CompletableFuture<Suggestions> {
    if (suggestions.isEmpty()) return Suggestions.empty()
    val range = StringRange.between(start + remaining.lastIndexOf(separator) + 1, input.length)
    val entries = suggestions.map { BrigadierSuggestion(range, it.text, tooltip(it)?.let(::LiteralMessage)) }
    return CompletableFuture.completedFuture(Suggestions(range, entries))
}

internal fun <S> suggesting(
    tooltips: Tooltips<S>,
    separator: Char = ' ',
    items: (ctx: CommandContext<S>, remaining: String, typed: String) -> List<Suggestion>,
): SuggestionProvider<S> = SuggestionProvider { ctx, builder ->
    builder.reply(items(ctx, builder.remaining, builder.typed(separator)), separator) { entry ->
        entry.tooltip?.let { tooltips.of(ctx.source, it, *entry.args.toTypedArray()) }
    }
}

internal fun rank(names: List<String>, raw: String, limit: Int = Int.MAX_VALUE): List<String> {
    val needle = raw.lowercase()
    if (needle.isEmpty()) return names.take(limit)
    return names
        .mapNotNull { name ->
            val lower = name.lowercase()
            val score = when {
                lower == needle -> 0
                lower.startsWith(needle) -> 1
                lower.contains(needle) -> 2
                else -> return@mapNotNull null
            }
            score to name
        }
        .sortedBy { it.first }
        .take(limit)
        .map { it.second }
}

internal fun List<Suggestion>.startingWith(typed: String): List<Suggestion> =
    filter { it.text.startsWith(typed, ignoreCase = true) }
