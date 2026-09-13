package com.dreamdisplays.platform.server.commands.subcommands

import java.util.*

/** Filter keywords accepted by `/display list`. */
internal enum class ListFilter(val token: String) {
    MINE("mine"),
    WORLD("world"),
    OWNER("owner"),
    SYNC("sync");

    companion object {
        val tokens: List<String> = entries.map { it.token }

        fun fromToken(raw: String?): ListFilter? {
            val token = raw?.lowercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.token == token }
        }
    }
}
