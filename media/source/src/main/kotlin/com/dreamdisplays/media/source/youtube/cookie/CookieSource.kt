package com.dreamdisplays.media.source.youtube.cookie

import java.util.*

/**
 * Browsers `yt-dlp` can export cookies from via `--cookies-from-browser`, plus [NONE] for the
 * disabled state. [browserName] is the exact token `yt-dlp` expects; null for [NONE].
 */
enum class CookieSource(val browserName: String?) {
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
         * Parses a raw config value into a [CookieSource]: [NONE] for empty / disabled aliases, a matching browser for
         * a recognized token, or null otherwise.
         */
        fun fromConfig(raw: String): CookieSource? {
            val v = raw.trim().lowercase(Locale.ENGLISH)
            if (v.isEmpty() || v in DISABLED_ALIASES) return NONE
            return entries.firstOrNull { it.browserName == v }
        }
    }
}
