package com.dreamdisplays.api.media.source.url

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.security.model.MediaHttpUrl
import java.util.*

/**
 * Recognizes and dissects Kick URLs (live channels, VODs, direct videos).
 *
 * @since 1.9.x
 */
@Unstable
object KickUrls {
    /** First path segments that are Kick site pages, not channel slugs. */
    private val RESERVED_SLUGS = setOf(
        "video", "videos", "browse", "following", "categories", "category", "search", "clips",
        "about", "help", "settings", "messages", "subscriptions", "dashboard", "api", "js", "css",
        "wallet", "friends", "welcome", "download", "community", "careers", "privacy", "terms",
    )

    /** A Kick video is identified by a UUID. */
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** A channel slug: lowercase letters, digits, and underscores, as Kick allows. */
    private val SLUG_RE = Regex("^[A-Za-z0-9_]{2,25}$")

    /** True when [url] points at a Kick channel or video. */
    fun isKick(url: String): Boolean = parse(url) != null

    /** Parses [url] into a [com.dreamdisplays.api.media.source.MediaSource.Kick] or null if not recognizable. */
    fun parse(url: String): MediaSource.Kick? {
        val parsed = MediaHttpUrl.parse(url) ?: MediaHttpUrl.parse("https://${url.trim()}") ?: return null
        val host = parsed.uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        if (host != "kick.com") return null

        val segments = parsed.uri.path?.split('/')?.filter { it.isNotBlank() } ?: return null
        if (segments.isEmpty()) return null

        // Store the scheme-normalized URL, never the raw paste: a scheme-less "kick.com/xqc" would
        // otherwise be rejected by the server's http(s)-only URL policy.
        val normalized = parsed.value

        // kick.com/video/<uuid> — a VOD addressed directly, with no channel in the path
        if (segments[0] == "video" && segments.getOrNull(1)?.matches(UUID_RE) == true) {
            return MediaSource.Kick(url = normalized, videoUuid = segments[1])
        }

        val slug = segments[0]
        if (slug.lowercase(Locale.ROOT) in RESERVED_SLUGS || !slug.matches(SLUG_RE)) return null

        // kick.com/<slug>/videos/<uuid> — a channel VOD
        if (segments.getOrNull(1) == "videos" && segments.getOrNull(2)?.matches(UUID_RE) == true) {
            return MediaSource.Kick(url = normalized, channel = slug, videoUuid = segments[2])
        }

        // kick.com/<slug> — the live channel
        if (segments.size == 1) return MediaSource.Kick(url = normalized, channel = slug)
        return null
    }

    /** Returns [query] as a channel slug when it looks like one (for live-channel search-by-name). */
    fun channelSlugCandidate(query: String): String? {
        val q = query.trim()
        return if (q.matches(SLUG_RE) && q.lowercase(Locale.ROOT) !in RESERVED_SLUGS) q.lowercase(Locale.ROOT) else null
    }
}
