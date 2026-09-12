package com.dreamdisplays.media.player.subtitle

/** Minimal, but enough for `Dream Displays` WebVTT parser. */
object WebVttParser {
    /** Matches a cue timing line, e.g. "00:00:01.000 -> 00:00:04.000 line:90%". */
    private val timingLine = Regex(
        """(\d{2}:)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(\d{2}:)?(\d{2}):(\d{2})[.,](\d{3})""",
    )

    /** Strips WebVTT / HTML-style inline tags such as `<b>`, `</b>`, `<00:00:01.000><c>`. */
    private val inlineTag = Regex("<[^>]*>")

    /** Parses [content] into a time-ordered list of cues. */
    fun parse(content: String): List<SubtitleCue> {
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<SubtitleCue>()
        var i = 0
        while (i < lines.size) {
            val match = timingLine.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val start = toNanos(match, 1)
            val end = toNanos(match, 5)
            i++
            val text = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(lines[i].replace(inlineTag, ""))
                i++
            }
            val cleaned = text.toString().trim()
            if (cleaned.isNotEmpty() && end > start) cues.add(SubtitleCue(start, end, cleaned))
        }
        return cues
    }

    private fun toNanos(match: MatchResult, groupOffset: Int): Long {
        val g = match.groupValues
        val hours = g[groupOffset].removeSuffix(":").toLongOrNull() ?: 0L
        val minutes = g[groupOffset + 1].toLongOrNull() ?: 0L
        val seconds = g[groupOffset + 2].toLongOrNull() ?: 0L
        val millis = g[groupOffset + 3].toLongOrNull() ?: 0L
        return ((hours * 3600 + minutes * 60 + seconds) * 1000 + millis) * 1_000_000L
    }

    /** Returns the cue active at [positionNanos], or null when none is showing. */
    fun cueAt(cues: List<SubtitleCue>, positionNanos: Long): SubtitleCue? {
        if (cues.isEmpty()) return null
        var lo = 0
        var hi = cues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cue = cues[mid]
            when {
                positionNanos < cue.startNanos -> hi = mid - 1
                positionNanos >= cue.endNanos -> lo = mid + 1
                else -> return cue
            }
        }
        return null
    }
}
