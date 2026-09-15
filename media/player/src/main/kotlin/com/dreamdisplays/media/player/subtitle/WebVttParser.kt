package com.dreamdisplays.media.player.subtitle

/** Minimal, but enough for `Dream Displays` WebVTT parser. */
object WebVttParser {
    private val timingLine = Regex(
        """(\d{2}:)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(\d{2}:)?(\d{2}):(\d{2})[.,](\d{3})""",
    )

    private val inlineTag = Regex("<[^>]*>")

    private val entity = Regex("""&(#[xX][0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")

    private val whitespaceRun = Regex("\\s+")

    private val namedEntities = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "lrm" to "", "rlm" to "",
    )

    private const val TRANSITION_CUE_NANOS = 50_000_000L

    private const val MAX_BRIDGED_GAP_NANOS = 250_000_000L

    /** Parses [content] into a time-ordered, non-overlapping list of cues. */
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
            val textLines = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                textLines.add(lines[i])
                i++
            }
            val cleaned = cleanText(textLines)
            if (cleaned.isNotEmpty() && end > start) cues.add(SubtitleCue(start, end, cleaned))
        }
        return smooth(cues)
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

    private fun cleanText(lines: List<String>): String =
        lines.asSequence()
            .map { decodeEntities(it.replace(inlineTag, "")).replace(whitespaceRun, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun decodeEntities(text: String): String {
        if ('&' !in text && ' ' !in text) return text
        return text.replace(entity) { m ->
            val ref = m.groupValues[1]
            when {
                ref.startsWith("#x") || ref.startsWith("#X") ->
                    ref.substring(2).toIntOrNull(16)?.let(::codePointText) ?: m.value

                ref.startsWith("#") -> ref.substring(1).toIntOrNull()?.let(::codePointText) ?: m.value
                else -> namedEntities[ref.lowercase()] ?: m.value
            }
        }.replace(' ', ' ')
    }

    private fun codePointText(cp: Int): String? =
        if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null

    private fun smooth(cues: List<SubtitleCue>): List<SubtitleCue> {
        val sorted = cues
            .filter { it.endNanos - it.startNanos > TRANSITION_CUE_NANOS }
            .sortedBy { it.startNanos }
        val out = ArrayList<SubtitleCue>(sorted.size)
        for ((index, cue) in sorted.withIndex()) {
            val next = sorted.getOrNull(index + 1)
            val end = if (next != null && next.startNanos - cue.endNanos <= MAX_BRIDGED_GAP_NANOS) {
                next.startNanos
            } else {
                cue.endNanos
            }
            if (end <= cue.startNanos) continue
            val prev = out.lastOrNull()
            if (prev != null && prev.text == cue.text && prev.endNanos == cue.startNanos) {
                out[out.lastIndex] = prev.copy(endNanos = end)
            } else {
                out.add(if (end == cue.endNanos) cue else cue.copy(endNanos = end))
            }
        }
        return out
    }

    private fun toNanos(match: MatchResult, groupOffset: Int): Long {
        val g = match.groupValues
        val hours = g[groupOffset].removeSuffix(":").toLongOrNull() ?: 0L
        val minutes = g[groupOffset + 1].toLongOrNull() ?: 0L
        val seconds = g[groupOffset + 2].toLongOrNull() ?: 0L
        val millis = g[groupOffset + 3].toLongOrNull() ?: 0L
        return ((hours * 3600 + minutes * 60 + seconds) * 1000 + millis) * 1_000_000L
    }
}
