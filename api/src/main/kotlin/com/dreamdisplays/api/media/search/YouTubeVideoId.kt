package com.dreamdisplays.api.media.search

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * A validated 11-character YouTube video id.
 *
 * @since 1.8.4
 */
@JvmInline
@DreamDisplaysUnstableApi
value class YouTubeVideoId(val value: String) {
    init {
        require(isValid(value)) { "Invalid YouTube video id: $value" }
    }

    /** To string representation of this YouTube video id. */
    override fun toString(): String = value

    companion object {
        /** Valid ID. */
        private val VALID_ID = Regex("^[A-Za-z0-9_-]{11}$")

        /** Parses a bare YouTube id, stripping common URL suffixes when callers pass a path segment. */
        fun parse(value: String?): YouTubeVideoId? {
            val cleaned = value
                ?.trim()
                ?.substringBefore('?')
                ?.substringBefore('&')
                ?.substringBefore('#')
                ?: return null

            return if (isValid(cleaned)) YouTubeVideoId(cleaned) else null
        }

        /** Parses [value] or throws [IllegalArgumentException] when it is not a valid YouTube id. */
        fun require(value: String): YouTubeVideoId =
            parse(value) ?: throw IllegalArgumentException("Invalid YouTube video id: $value")

        /** Returns true when [value] is exactly a YouTube video id. */
        fun isValid(value: String): Boolean = VALID_ID.matches(value)
    }
}
