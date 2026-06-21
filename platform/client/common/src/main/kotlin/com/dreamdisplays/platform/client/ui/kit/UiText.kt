package com.dreamdisplays.platform.client.ui.kit

import net.minecraft.client.gui.Font

/**
 * Font-aware text measurement helpers shared by the UI: ellipsis trimming and word wrapping.
 * Pulled out of the individual screens so the logic exists once.
 */
object UiText {
    private const val ELLIPSIS = "..."

    /** Returns [s] unchanged if it fits in [maxW] pixels, otherwise trims it and appends an ellipsis. */
    fun trim(font: Font, s: String, maxW: Int): String {
        if (font.width(s) <= maxW) return s
        val dotsW = font.width(ELLIPSIS)
        val sb = StringBuilder()
        for (c in s) {
            if (font.width(sb.toString() + c) + dotsW > maxW) break
            sb.append(c)
        }
        return "$sb$ELLIPSIS"
    }

    /** Word-wraps [s] into at most [maxLines] lines of [maxW] pixels; over-long words are ellipsis-trimmed. */
    fun wrap(font: Font, s: String, maxW: Int, maxLines: Int): List<String> {
        val out = ArrayList<String>()
        val words = s.split(Regex("\\s+"))
        val cur = StringBuilder()
        for (word in words) {
            val trial = if (cur.isEmpty()) word else "$cur $word"
            if (font.width(trial) <= maxW) {
                cur.setLength(0); cur.append(trial)
            } else {
                if (cur.isNotEmpty()) {
                    out.add(cur.toString())
                    if (out.size == maxLines) break
                    cur.setLength(0)
                }
                if (font.width(word) > maxW) {
                    out.add(trim(font, word, maxW))
                    if (out.size == maxLines) break
                } else {
                    cur.append(word)
                }
            }
        }
        if (cur.isNotEmpty() && out.size < maxLines) out.add(cur.toString())
        if (out.isEmpty()) out.add("")
        return out
    }

    /** Formats [nanos] of playback time as `mm:ss` or `h:mm:ss`. */
    fun formatTime(nanos: Long): String {
        if (nanos <= 0) return "00:00"
        val s = nanos / 1_000_000_000L
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }
}
