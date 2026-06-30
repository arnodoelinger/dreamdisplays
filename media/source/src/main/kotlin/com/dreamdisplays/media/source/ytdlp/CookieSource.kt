package com.dreamdisplays.media.source.ytdlp

import java.util.Locale

/**
 * Browsers `yt-dlp` can export cookies from via `--cookies-from-browser`, plus [NONE] for the
 * disabled state. [browserName] is the exact token `yt-dlp` expects; null for [NONE].
 *
 * @property browserName the `yt-dlp` browser token, or null when cookie export is disabled.
 */
enum class CookieSource(val browserName: String?) {
    /** Cookie export disabled. */
    NONE(null),

    BRAVE("brave"),
    CHROME("chrome"),
    CHROMIUM("chromium"),
    EDGE("edge"),
    FIREFOX("firefox"),
    OPERA("opera"),
    SAFARI("safari"),
    VIVALDI("vivaldi"),
    WHALE("whale");

    /** True when no browser is selected and cookie export is off. */
    val isDisabled: Boolean get() = this == NONE

    /** Value written to `ytdlp-cookies-from-browser` in client config. */
    val configToken: String get() = browserName ?: DISABLED_TOKEN

    companion object {
        private const val DISABLED_TOKEN = "none"

        /** Config values, besides an empty string, that explicitly disable cookie export. */
        private val DISABLED_ALIASES = setOf(DISABLED_TOKEN, "off", "disabled", "auto")

        /**
         * Parses a raw config value into a [CookieSource].
         *
         * @return [NONE] for an empty string or a disabled-alias; the matching browser constant for
         *   a recognized token; or null for an unrecognized non-empty value, so the caller can decide
         *   whether to warn or attempt it as a raw yt-dlp browser name.
         */
        fun fromConfig(raw: String): CookieSource? {
            val v = raw.trim().lowercase(Locale.ENGLISH)
            if (v.isEmpty() || v in DISABLED_ALIASES) return NONE
            return entries.firstOrNull { it.browserName == v }
        }
    }
}
