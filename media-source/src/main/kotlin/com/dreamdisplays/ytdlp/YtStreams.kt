package com.dreamdisplays.ytdlp

/**
 * Shared helpers over resolved [YtStream] lists, used by both the in-process [NewPipeResolver] fast
 * path and the `yt-dlp` fallback so the "is this result good enough?" decision stays in one place.
 */
internal object YtStreams {
    /** Distinct heights, or a single track at least this tall, needed to count as a real quality choice. */
    private const val LADDER_MIN_DISTINCT_HEIGHTS = 2
    private const val LADDER_MIN_SINGLE_HEIGHT = 720

    /** The distinct video heights present across [streams], ignoring audio-only tracks. */
    fun distinctHeights(streams: List<YtStream>): List<Int> =
        streams.asSequence()
            .filter { it.hasVideo() }
            .mapNotNull { it.height }
            .distinct()
            .toList()

    /**
     * True when [streams] gives the player an actual quality choice (>=2 distinct heights, or a
     * single track of at least 720p). YouTube's adaptive (video-only) tracks are often unavailable
     * to the fast path, leaving only a muxed 360p stream; that does not count as a ladder.
     */
    fun offersQualityLadder(streams: List<YtStream>): Boolean {
        val heights = distinctHeights(streams)
        return heights.size >= LADDER_MIN_DISTINCT_HEIGHTS || (heights.maxOrNull() ?: 0) >= LADDER_MIN_SINGLE_HEIGHT
    }
}
