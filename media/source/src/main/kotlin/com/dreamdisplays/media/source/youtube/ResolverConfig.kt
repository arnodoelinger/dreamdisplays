package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.media.source.youtube.cookie.CookieSource

/**
 * Host-supplied configuration the media resolvers need (proxy, browser-cookie source). The platform
 * layer installs a [Provider] at startup; until then sane defaults apply. Keeps the resolver module
 * free of any dependency on the Minecraft client config.
 */
object ResolverConfig {
    interface Provider {
        /** `yt-dlp` proxy URL, or blank for none. */
        val ytdlpProxy: String

        /** Browser to import cookies from, or [CookieSource.NONE]. */
        val ytdlpCookieSource: CookieSource
    }

    /** Default values until the host installs a real provider. */
    private object Defaults : Provider {
        /** Default `yt-dlp` proxy URL, or blank for none. */
        override val ytdlpProxy: String = ""

        /** Default browser to import cookies from, or [CookieSource.NONE]. */
        override val ytdlpCookieSource: CookieSource = CookieSource.NONE
    }

    /**
     * The host must set this to a real provider at startup, before any resolver is used. Until then
     * the defaults apply.
     */
    @Volatile
    var provider: Provider = Defaults

    /** Current `yt-dlp` proxy URL, or blank for none. */
    val ytdlpProxy: String get() = provider.ytdlpProxy

    /** Current browser to import cookies from, or [CookieSource.NONE]. */
    val ytdlpCookieSource: CookieSource get() = provider.ytdlpCookieSource
}
