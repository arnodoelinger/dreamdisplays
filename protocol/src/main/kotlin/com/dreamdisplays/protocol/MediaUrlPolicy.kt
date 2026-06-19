package com.dreamdisplays.protocol

/**
 * Trust-boundary policy for client-supplied media URLs.
 */
object MediaUrlPolicy {
    private val BARE_YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")

    /**
     * A bare 11-character YouTube id (which may legitimately start with `-`), or a trimmed
     * `http://` / `https://` URL with no whitespace or control characters.
     */
    fun isAllowed(url: String): Boolean {
        if (url.isEmpty()) return true
        val s = url.trim()
        if (s.isEmpty()) return false
        if (s.any { it.isWhitespace() || it.isISOControl() }) return false
        if (s.length == 11 && BARE_YOUTUBE_ID.matches(s)) return true
        val lower = s.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}
