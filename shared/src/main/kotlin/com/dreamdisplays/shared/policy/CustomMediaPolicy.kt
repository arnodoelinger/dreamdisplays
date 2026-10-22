package com.dreamdisplays.shared.policy

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.source.MediaPlatform
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.MediaHttpUrl
import java.util.*

/** Server-side policy for custom media URLs (non-platform links players paste). */
@Unstable
object CustomMediaPolicy {
    /** The `[custom_media]` config section, in the shape the check needs. */
    data class Settings(
        val enabled: Boolean = true,
        val allowedHosts: List<String> = emptyList(),
        val blockedHosts: List<String> = emptyList(),
    ) {
        companion object {
            val DEFAULT: Settings = Settings()
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

    /** True when [url] is a custom link rather than one of the explicitly supported platforms. */
    fun isCustom(url: String): Boolean {
        if (url.isBlank()) return false
        return when (MediaSource.from(url).platform) {
            MediaPlatform.YOUTUBE, MediaPlatform.TWITCH, MediaPlatform.VIMEO, MediaPlatform.KICK,
            MediaPlatform.BILIBILI, MediaPlatform.TIKTOK, MediaPlatform.DOUYIN,
            -> false

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

    private fun matches(host: String, pattern: String): Boolean {
        val rule = pattern.trim().lowercase(Locale.ROOT).removePrefix("*.").removePrefix(".")
        if (rule.isEmpty()) return false
        return host == rule || host.endsWith(".$rule")
    }
}
