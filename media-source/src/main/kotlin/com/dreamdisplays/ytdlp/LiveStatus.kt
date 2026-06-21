package com.dreamdisplays.ytdlp

/**
 * Values of `yt-dlp`'s `live_status` metadata field. [wireValue] is the exact JSON token yt-dlp
 * emits; [UNKNOWN] covers an absent or unrecognized value.
 *
 * @property wireValue the literal `live_status` string, or null for [UNKNOWN].
 */
enum class LiveStatus(val wireValue: String?) {
    /** A regular video that is not and was never a live broadcast. */
    NOT_LIVE("not_live"),

    /** Currently broadcasting live. */
    IS_LIVE("is_live"),

    /** A scheduled live broadcast that has not started yet. */
    IS_UPCOMING("is_upcoming"),

    /** A broadcast that just ended; the VOD is being processed. */
    POST_LIVE("post_live"),

    /** A finished broadcast whose VOD is fully available. */
    WAS_LIVE("was_live"),

    /** Field absent or unrecognized. */
    UNKNOWN(null);

    /** True for states the player treats as a live stream (live, upcoming, or just-ended post-live). */
    val isLiveLike: Boolean get() = this == IS_LIVE || this == IS_UPCOMING || this == POST_LIVE

    companion object {
        /** Maps a raw `live_status` token to its constant; unknown or null tokens yield [UNKNOWN]. */
        fun fromWire(value: String?): LiveStatus = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}
