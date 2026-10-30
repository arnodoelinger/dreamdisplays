package com.dreamdisplays.api.util

/**
 * Enum-like value with a stable wire token.
 *
 * @since 1.8.x
 */
interface WireEnum {
    val wire: String
}

/**
 * Finds a wire enum value among [entries] by token, returning [default] when [raw] is blank or unknown.
 *
 * @since 1.8.x
 */
fun <E> wireEnumValueOf(raw: String?, default: E, entries: Collection<E>): E
        where E : Enum<E>, E : WireEnum =
    wireEnumValueOfOrNull(raw, entries) ?: default

/**
 * Finds a wire enum value among [entries] by token, or `null` when [raw] is blank or unknown.
 *
 * @since 1.8.x
 */
fun <E> wireEnumValueOfOrNull(raw: String?, entries: Collection<E>): E?
        where E : Enum<E>, E : WireEnum {
    val token = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return entries.firstOrNull { it.wire == token }
}
