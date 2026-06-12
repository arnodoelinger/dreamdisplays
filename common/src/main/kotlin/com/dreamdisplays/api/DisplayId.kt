@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.api

import java.util.UUID

/**
 * A unique identifier for a display.
 *
 * @since 1.0.0
 */
@JvmInline value class DisplayId(val uuid: UUID) {
    /** Returns the string representation of the display ID. */
    override fun toString(): String = uuid.toString()

    companion object {
        /** Generates a new random display ID. */
        fun random(): DisplayId = DisplayId(UUID.randomUUID()) // TODO: use a better id generator in 2.0.0

        /** Creates a display ID from the given string. Throws an exception if the string is not a valid UUID. */
        fun from(string: String): DisplayId = DisplayId(UUID.fromString(string))

        /** Creates a display ID from the given string, or returns null if the string is not a valid UUID. */
        fun fromOrNull(string: String): DisplayId? = runCatching { from(string) }.getOrNull()
    }
}
