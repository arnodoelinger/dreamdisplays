package com.dreamdisplays.api.display.model.property

import java.util.*

/**
 * A unique identifier for a display.
 *
 * @since 1.0.x
 */
data class DisplayId(val uuid: UUID) {
    override fun toString(): String = uuid.toString()

    companion object {
        /** Creates a display ID from the given string. Throws an exception if the string is not a valid UUID. */
        fun from(string: String): DisplayId = DisplayId(UUID.fromString(string))
    }
}
