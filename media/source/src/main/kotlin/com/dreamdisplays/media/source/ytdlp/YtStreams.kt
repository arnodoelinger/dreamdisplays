package com.dreamdisplays.media.source.ytdlp

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

    /**
     * Compact one-line description of the googlevideo URL parameters that govern server-side
     * throttling, taken from the tallest video stream in [streams] (the closest proxy for what
     * playback actually uses — the first entry is typically the muxed 360p track): the serving
     * client (`c`), `itag`, whether the anti-throttling `n` parameter is present, `ratebypass`,
     * and the full height ladder.
     *
     * A missing or un-deobfuscated `n` is the classic cause of
     * YouTube capping transfers to ~1x playback speed.
     */
    fun throttleMarkers(streams: List<YtStream>): String {
        val tallest = streams.filter { it.hasVideo() }.maxByOrNull { it.height ?: 0 }
            ?: return "no video stream"
        val query = tallest.url.substringAfter('?', "")
        val params = query.split('&').mapNotNull {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            if (k.isEmpty()) null else k to v
        }.toMap()
        val n = params["n"]
        return "c=${params["c"] ?: "?"} itag=${params["itag"] ?: "?"} " +
                "n=${if (n == null) "ABSENT" else "present(len=${n.length})"} " +
                "ratebypass=${params["ratebypass"] ?: "-"} " +
                "heights=${distinctHeights(streams).sorted()}"
    }
}
