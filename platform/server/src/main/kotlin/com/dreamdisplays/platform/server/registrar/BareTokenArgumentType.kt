package com.dreamdisplays.platform.server.registrar

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType

/**
 * Single space-delimited token, unquoted. Brigadier's stock `StringArgumentType.word()`/`.string()`
 * only allow `@` / `%` / `:` / `/` etc. when the whole token is wrapped in quotes, since their unquoted
 * charset excludes those characters; this type just reads up to the next space, so selectors
 * (`@a`, `%group`) and raw URLs (`https://...`) both work bare. Used for `/display fullscreen`'s
 * `target` (player / selector list) and `id` (display id or video URL) arguments.
 */
object BareTokenArgumentType : ArgumentType<String> {
    private val MISSING = SimpleCommandExceptionType(LiteralMessage("Expected a value"))

    override fun parse(reader: StringReader): String {
        val start = reader.cursor
        while (reader.canRead() && reader.peek() != ' ') reader.skip()
        if (reader.cursor == start) throw MISSING.create()
        return reader.string.substring(start, reader.cursor)
    }
}
