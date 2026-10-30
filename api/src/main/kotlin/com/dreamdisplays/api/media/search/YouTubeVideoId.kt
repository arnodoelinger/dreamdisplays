package com.dreamdisplays.api.media.search

/**
 * A validated 11-character YouTube video ID.
 *
 * @since 1.8.x
 */
data class YouTubeVideoId(val value: String) {
    init {
        require(isValid(value)) { "Invalid YouTube video id: $value." }
    }

    override fun toString(): String = value

    companion object {
        private val VALID_ID = Regex("^[A-Za-z0-9_-]{11}$")

        /** Parses a YouTube ID. */
        fun parse(value: String?): YouTubeVideoId? {
            val cleaned = value
                ?.trim()
                ?.substringBefore('?')
                ?.substringBefore('&')
                ?.substringBefore('#')
                ?: return null

            return if (isValid(cleaned)) YouTubeVideoId(cleaned) else null
        }

        /** Parses [value] or throws [IllegalArgumentException] when it is not a valid YouTube ID. */
        fun require(value: String): YouTubeVideoId =
            parse(value) ?: throw IllegalArgumentException("Invalid YouTube video id: $value")

        /** @return `true` when [value] is exactly a YouTube video ID. */
        fun isValid(value: String): Boolean = VALID_ID.matches(value)
    }
}
