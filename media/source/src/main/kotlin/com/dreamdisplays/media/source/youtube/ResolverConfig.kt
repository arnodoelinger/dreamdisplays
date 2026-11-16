package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.media.source.youtube.cookie.CookieSource

/**
 * Host-supplied configuration the media resolvers need (proxy, browser-cookie source). The platform
 * layer installs a [Provider] at startup; until then sane defaults apply. Keeps the resolver module
 * free of any dependency on the Minecraft client config.
 */
object ResolverConfig {
    interface Provider {
        val ytdlpProxy: String
        val ytdlpCookieSource: CookieSource
        val ytdlpJsRuntime: String get() = "auto"
    }

    private object Defaults : Provider {
        override val ytdlpProxy: String = ""
        override val ytdlpCookieSource: CookieSource = CookieSource.NONE
    }

    @Volatile
    var provider: Provider = Defaults
    val ytdlpProxy: String get() = provider.ytdlpProxy
    val ytdlpCookieSource: CookieSource get() = provider.ytdlpCookieSource
    val ytdlpJsRuntime: String get() = provider.ytdlpJsRuntime
}
