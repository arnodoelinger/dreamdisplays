package com.dreamdisplays.api.security

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import java.net.URI
import java.util.*

/**
 * A validated absolute HTTP(S) media URL.
 *
 * @since 1.8.4
 */
@JvmInline
@DreamDisplaysUnstableApi
value class MediaHttpUrl(val value: String) {
    init {
        require(parseUri(value) != null) { "Invalid HTTP media URL: $value" }
    }

    /** Parsed URI view of [value]. */
    val uri: URI get() = URI(value)

    /** To string. */
    override fun toString(): String = value

    companion object {
        /** Parses [input] as an absolute `http` or `https` URL with a host. */
        fun parse(input: String?, maxLength: Int = Int.MAX_VALUE): MediaHttpUrl? {
            val value = input?.trim() ?: return null
            if (value.isEmpty()) return null
            if (value.length > maxLength) return null
            if (parseUri(value) == null) return null
            return MediaHttpUrl(value)
        }

        /** Returns true when [input] is accepted by [parse]. */
        fun isValid(input: String?, maxLength: Int = Int.MAX_VALUE): Boolean =
            parse(input, maxLength) != null

        private fun parseUri(value: String): URI? {
            if (value.any { it.isWhitespace() || it.isISOControl() }) return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            return uri
        }
    }
}
