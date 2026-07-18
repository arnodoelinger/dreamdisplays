package com.dreamdisplays.api.security

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import java.util.*

/**
 * A client-supplied language tag after trust-boundary cleanup.
 *
 * @since 1.8.4
 */
@JvmInline
@DreamDisplaysUnstableApi
value class LanguageTag(val value: String) {
    init {
        require(value.none { it.isWhitespace() || it.isISOControl() }) {
            "Language tag must not contain whitespace or control characters."
        }
    }

    /** To string representation of this language tag. */
    override fun toString(): String = value

    companion object {
        /** Drops whitespace / control characters and truncates to [maxLength]. */
        fun sanitize(raw: String?, maxLength: Int = MediaUrlPolicy.MAX_LANG_LENGTH): LanguageTag {
            val limit = maxLength.coerceAtLeast(0)
            val cleaned = raw.orEmpty()
                .asSequence()
                .filterNot { it.isWhitespace() || it.isISOControl() }
                .take(limit)
                .joinToString("")
            return LanguageTag(cleaned)
        }

        /** Normalizes user-facing audio-language input to the base language code used by playback. */
        fun canonicalAudioCode(raw: String?, maxLength: Int = MediaUrlPolicy.MAX_LANG_LENGTH): LanguageTag {
            val base = sanitize(raw, maxLength)
                .value
                .lowercase(Locale.ROOT)
                .replace('-', '_')
                .substringBefore('_')

            return LanguageTag(
                when (base) {
                    "ua" -> "uk"
                    else -> base
                }
            )
        }
    }
}
