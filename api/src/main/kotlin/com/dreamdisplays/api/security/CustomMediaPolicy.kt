@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.api.security

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.media.source.MediaPlatform
import com.dreamdisplays.api.media.source.MediaSource
import java.util.*

/**
 * Server-side policy for custom media: URLs that are not a supported platform page, i.e. the
 * arbitrary links players paste themselves.
 *
 * Platform sources (`YouTube`, `Twitch`) are never subject to this policy - they are already
 * constrained to their own hosts - so a server can forbid custom links without disabling the mod.
 * The check is pure and side-effect free so both server platforms can share it, and so it is
 * testable without a running server.
 *
 * This runs after [MediaUrlPolicy] (URL shape) and is independent of the client's SSRF guard:
 * it answers "is this player allowed to point a display at this host", not "is this URL safe".
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
object CustomMediaPolicy {
    /**
     * The `[custom_media]` config section, in the shape the check needs.
     *
     * @property enabled false turns custom links off entirely; only platform URLs are accepted.
     * @property allowedHosts when non-empty, an allowlist - every other host is refused.
     * @property blockedHosts hosts always refused, checked before [allowedHosts].
     */
    data class Settings(
        val enabled: Boolean = true,
        val allowedHosts: List<String> = emptyList(),
        val blockedHosts: List<String> = emptyList(),
    ) {
        companion object {
            /** Permissive defaults, used by clients and by servers with no `[custom_media]` section. */
            val DEFAULT = Settings()
        }
    }

    /** Outcome of [evaluate]; every non-[ALLOWED] value maps to its own player-facing message. */
    enum class Verdict {
        /** The URL may be applied to the display. */
        ALLOWED,

        /** Custom links are switched off on this server. */
        DISABLED,

        /** The URL's host is on the server's blocklist. */
        HOST_BLOCKED,

        /** The server runs an allowlist and this host is not on it. */
        HOST_NOT_ALLOWED,

        /** The URL carries no parseable host, so no host rule can be applied to it. */
        MALFORMED,
    }

    /**
     * True when [url] is a custom link rather than a supported platform page. The first-party
     * platforms (YouTube, Twitch, Vimeo, Kick) are never "custom" — they are always allowed, exactly
     * like YouTube always was - so disabling custom media restricts only direct files and long-tail
     * links, never a supported platform. A blank URL is not custom either: it clears the display,
     * which no rule here has any business refusing.
     */
    fun isCustom(url: String): Boolean {
        if (url.isBlank()) return false
        return when (MediaSource.from(url).platform) {
            MediaPlatform.YOUTUBE, MediaPlatform.TWITCH, MediaPlatform.VIMEO, MediaPlatform.KICK -> false
            MediaPlatform.DIRECT, MediaPlatform.OTHER -> true
        }
    }

    /**
     * Decides whether [url] may be set on a display under [settings]. Blank URLs (clearing a
     * display) and platform URLs always pass; everything else is matched against the host rules.
     */
    fun evaluate(url: String, settings: Settings): Verdict {
        if (url.isBlank()) return Verdict.ALLOWED
        if (!isCustom(url)) return Verdict.ALLOWED
        if (!settings.enabled) return Verdict.DISABLED

        val host = MediaHttpUrl.parse(url)?.uri?.host?.lowercase(Locale.ROOT)
            ?: return Verdict.MALFORMED
        if (settings.blockedHosts.any { matches(host, it) }) return Verdict.HOST_BLOCKED
        if (settings.allowedHosts.isNotEmpty() && settings.allowedHosts.none { matches(host, it) }) {
            return Verdict.HOST_NOT_ALLOWED
        }
        return Verdict.ALLOWED
    }

    /**
     * Matches [host] against one configured [pattern]: an exact host, or a domain that also covers
     * its subdomains (`example.com` matches `cdn.example.com`). A leading `*.` or `.` is accepted
     * so operators can write the rule either way round.
     */
    private fun matches(host: String, pattern: String): Boolean {
        val rule = pattern.trim().lowercase(Locale.ROOT).removePrefix("*.").removePrefix(".")
        if (rule.isEmpty()) return false
        return host == rule || host.endsWith(".$rule")
    }
}
