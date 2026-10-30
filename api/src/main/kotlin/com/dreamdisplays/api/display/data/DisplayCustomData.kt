package com.dreamdisplays.api.display.data

import com.dreamdisplays.api.CommandResult

/**
 * Extra data an addon keeps on a display. The server stores it with the display and sends it to every client that
 * sees the display, so a server-side addon and its client-side half share state without a network channel or a file of
 * their own.
 *
 * Keys look like `addon-id:name` (lowercase letters, digits and `_ . / -`), values are raw bytes: pick your own
 * encoding. See [DisplayCustomDataLimits] for the size limits.
 *
 * @since 1.10.x
 */
interface DisplayCustomData {
    /** Keys that currently have a value. */
    val keys: Set<String>

    /** The value stored under [key], or null. */
    operator fun get(key: String): ByteArray?

    /** True when nothing is stored. */
    val isEmpty: Boolean
}

/**
 * [DisplayCustomData] you can change. Every change is saved with the display and sent to the players who see it.
 *
 * @since 1.10.x
 */
interface MutableDisplayCustomData : DisplayCustomData {
    /**
     * Stores [value] under [key].
     *
     * [CommandResult.REFUSED] when the key or the size is not allowed, nothing changes.
     */
    operator fun set(key: String, value: ByteArray): CommandResult

    /** Removes [key]. [CommandResult.OK] even when it was not there. */
    fun remove(key: String): CommandResult

    /** Removes everything this display has stored. */
    fun clear(): CommandResult
}

/**
 * What [DisplayCustomData] allows, so one addon cannot bloat every display or every packet.
 *
 * @since 1.10.x
 */
object DisplayCustomDataLimits {
    /** Most keys per display. */
    const val MAX_ENTRIES = 32

    /** Largest single value, in bytes. */
    const val MAX_VALUE_BYTES = 16 * 1024

    /** Largest total of all values of one display, in bytes. */
    const val MAX_TOTAL_BYTES = 64 * 1024

    /** Longest key, in characters. */
    const val MAX_KEY_LENGTH = 64

    private val KEY = Regex("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+")

    /** True when [key] is `namespace:name` in the allowed characters and length. */
    @JvmStatic
    fun isValidKey(key: String): Boolean = key.length <= MAX_KEY_LENGTH && KEY.matches(key)

    /** True when putting [value] under [key] into [current] stays inside every limit. */
    @JvmStatic
    fun accepts(current: Map<String, ByteArray>, key: String, value: ByteArray): Boolean {
        if (!isValidKey(key) || value.size > MAX_VALUE_BYTES) return false
        val others = current.filterKeys { it != key }
        return others.size + 1 <= MAX_ENTRIES && others.values.sumOf { it.size } + value.size <= MAX_TOTAL_BYTES
    }

    /** True when the whole [data] map obeys every limit (used for data that arrives from a server). */
    @JvmStatic
    fun isValid(data: Map<String, ByteArray>): Boolean =
        data.size <= MAX_ENTRIES && data.values.sumOf { it.size } <= MAX_TOTAL_BYTES &&
            data.all { (k, v) -> isValidKey(k) && v.size <= MAX_VALUE_BYTES }
}
