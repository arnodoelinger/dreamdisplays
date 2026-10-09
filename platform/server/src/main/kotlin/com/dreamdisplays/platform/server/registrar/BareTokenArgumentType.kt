package com.dreamdisplays.platform.server.registrar

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType

/**
 * Space-delimited unquoted token for display targets, bare selectors, times and URLs.
 *
 * It needs no registry entry and no wire fallback: the `/display` tree is answered by the server
 * (see `answeredByServer`), so this type never reaches a client.
 */
object BareTokenArgumentType : ArgumentType<String> {
    private val MISSING = SimpleCommandExceptionType(LiteralMessage("Expected a value."))

    override fun parse(reader: StringReader): String {
        val start = reader.cursor
        while (reader.canRead() && reader.peek() != ' ') reader.skip()
        if (reader.cursor == start) throw MISSING.create()
        return reader.string.substring(start, reader.cursor)
    }
}
