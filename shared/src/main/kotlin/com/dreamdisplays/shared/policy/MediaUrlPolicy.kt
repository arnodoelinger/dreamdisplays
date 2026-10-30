package com.dreamdisplays.shared.policy

import com.dreamdisplays.api.media.search.YouTubeVideoId
import com.dreamdisplays.api.media.source.MediaHttpUrl

/** Trust-boundary policy for client-supplied media URLs. */
object MediaUrlPolicy {
    const val MAX_URL_LENGTH: Int = 2048
    const val MAX_LANG_LENGTH: Int = 16

    /**
     * Bounds a client-supplied audio-language code: drops whitespace / control characters and
     * truncates to [MAX_LANG_LENGTH]. Returns a value always safe to store and rebroadcast;
     * canonicalization (aliases, region suffixes) is the caller's concern.
     */
    fun sanitizeLang(lang: String): String = LanguageTag.sanitize(lang).value

    /**
     * A bare 11-character YouTube ID (which may legitimately start with `-`), or a trimmed
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
