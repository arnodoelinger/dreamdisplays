package com.dreamdisplays.shared.policy

import java.util.*

/** A client-supplied language tag after trust-boundary cleanup. */
data class LanguageTag(val value: String) {
    init {
        require(value.none { it.isWhitespace() || it.isISOControl() }) {
            "Language tag must not contain whitespace or control characters."
        }
    }

    override fun toString(): String = value

    companion object {
        fun sanitize(raw: String?, maxLength: Int = MediaUrlPolicy.MAX_LANG_LENGTH): LanguageTag {
            val limit = maxLength.coerceAtLeast(0)
            val cleaned = raw.orEmpty()
                .asSequence()
                .filterNot { it.isWhitespace() || it.isISOControl() }
                .take(limit)
                .joinToString("")
            return LanguageTag(cleaned)
        }

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
