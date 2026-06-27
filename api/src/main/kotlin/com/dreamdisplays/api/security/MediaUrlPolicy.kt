package com.dreamdisplays.api.security

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.media.search.YouTubeVideoId

/**
 * Trust-boundary policy for client-supplied media URLs.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
object MediaUrlPolicy {
    /**
     * Maximum accepted URL length. Comfortably above any legitimate media URL while capping the
     * amplification a single client can trigger: the server stores, persists and rebroadcasts the
     * URL to every viewer, each of whom then attempts to resolve it.
     */
    const val MAX_URL_LENGTH = 2048

    /** Maximum accepted audio-language length. */
    const val MAX_LANG_LENGTH = 16

    /**
     * Bounds a client-supplied audio-language code: drops whitespace / control characters and
     * truncates to [MAX_LANG_LENGTH]. Returns a value always safe to store and rebroadcast;
     * canonicalization (aliases, region suffixes) is the caller's concern.
     */
    fun sanitizeLang(lang: String): String = LanguageTag.sanitize(lang, MAX_LANG_LENGTH).value

    /**
     * A bare 11-character YouTube id (which may legitimately start with `-`), or a trimmed
     * `http://` or `https://` URL with no whitespace or control characters, within [MAX_URL_LENGTH].
     */
    fun isAllowed(url: String): Boolean {
        if (url.isEmpty()) return true
        if (url.length > MAX_URL_LENGTH) return false
        val s = url.trim()
        if (s.isEmpty()) return false
        if (s.length > MAX_URL_LENGTH) return false
        if (YouTubeVideoId.parse(s) != null) return true
        return MediaHttpUrl.isValid(s, MAX_URL_LENGTH)
    }
}
