package com.dreamdisplays.ytdlp

/**
 * Host-supplied configuration the media resolvers need (proxy, browser-cookie source). The platform
 * layer installs a [Provider] at startup; until then sane defaults apply. Keeps the resolver module
 * free of any dependency on the Minecraft client config.
 */
object ResolverConfig {
    /** Read-only view of the resolver-relevant settings. */
    interface Provider {
        /** `yt-dlp` proxy URL, or blank for none. */
        val ytdlpProxy: String

        /** Browser to import cookies from (e.g. "chrome"), or "none". */
        val ytdlpCookiesFromBrowser: String
    }

    private object Defaults : Provider {
        override val ytdlpProxy: String = ""
        override val ytdlpCookiesFromBrowser: String = "none"
    }

    @Volatile
    var provider: Provider = Defaults

    val ytdlpProxy: String get() = provider.ytdlpProxy
    val ytdlpCookiesFromBrowser: String get() = provider.ytdlpCookiesFromBrowser
}
