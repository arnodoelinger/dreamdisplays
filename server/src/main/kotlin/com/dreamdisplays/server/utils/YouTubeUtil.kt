package com.dreamdisplays.server.utils

import java.net.URI
import java.util.*

/** Utility functions for handling YouTube URLs and video IDs. */
object YouTubeUtil {
    private val SANITIZE_REGEX = "[^0-9A-Za-z+.-]".toRegex()
    private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    private val VIDEO_ID_IN_TEXT_REGEX = Regex("""(?:v=|/)([A-Za-z0-9_-]{11})(?=$|[?&#/])""")

    /** Extracts an 11-character YouTube video ID from a URL, partial URL, or bare ID. */
    fun extractVideoIdFromUri(url: String): String? {
        val input = url.trim()
        if (input.isEmpty()) return null
        normalizeVideoId(input)?.let { return it }
        val normalizedInput = if (input.contains("://")) input else "https://$input"
        return runCatching {
            extractVideoIdFromParsedUri(URI(normalizedInput))
        }.getOrNull() ?: extractVideoIdFromText(input)
    }

    /** Extracts an 11-character YouTube video ID from a URI. */
    private fun extractVideoIdFromParsedUri(uri: URI): String? {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val pathSegments = uri.path
            ?.split('/')
            ?.filter { it.isNotBlank() }
            .orEmpty()

        if (host == "youtu.be" || host.endsWith(".youtu.be")) {
            return pathSegments.firstNotNullOfOrNull { normalizeVideoId(it) }
        }

        val isYoutubeHost = host == "youtube.com" || host.endsWith(".youtube.com")
        if (!isYoutubeHost) return null

        uri.query?.let { query ->
            parseQueryParameter(query, "v")
                ?.let { normalizeVideoId(it) }
                ?.let { return it }
        }

        if (pathSegments.size >= 2) {
            val first = pathSegments[0].lowercase(Locale.ROOT)
            if (first in setOf("shorts", "embed", "live", "v")) {
                normalizeVideoId(pathSegments[1])?.let { return it }
            }
        }

        if (pathSegments.size == 1) {
            normalizeVideoId(pathSegments[0])?.let { return it }
        }

        return null
    }

    /** Extracts an 11-character YouTube video ID from a text string. */
    private fun extractVideoIdFromText(text: String): String? =
        VIDEO_ID_IN_TEXT_REGEX.find(text)?.groupValues?.getOrNull(1)?.let { normalizeVideoId(it) }

    /** Extracts a query parameter value from a URL query string. */
    private fun parseQueryParameter(query: String, paramName: String): String? =
        query.split("&").firstNotNullOfOrNull { param ->
            val (key, value) = param.split("=", limit = 2).takeIf { it.size == 2 } ?: return@firstNotNullOfOrNull null
            if (key == paramName) value else null
        }

    /** Strips characters unsafe for filesystem and URL usage from [raw]. */
    fun sanitize(raw: String?): String? = raw?.trim()?.replace(SANITIZE_REGEX, "")

    /** Normalizes a YouTube video ID by removing any URL parameters or fragments. */
    private fun normalizeVideoId(value: String): String? {
        val cleaned = value.trim().substringBefore('?').substringBefore('&').substringBefore('#')
        return cleaned.takeIf { VIDEO_ID_REGEX.matches(it) }
    }
}
