package com.dreamdisplays.ytdlp

import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * Shared opener for the YouTube HTTP paths ([YouTubeInnerTube] and [NewPipeResolver]): opens an
 * [HttpURLConnection], routing it through the proxy configured in `ytdlpProxy` if one is set.
 */
object ProxyConnections {
    private val logger = LoggerFactory.getLogger("DreamDisplays/ProxyConnections")

    /**
     * Opens an [HttpURLConnection] for [urlStr], routing through the configured proxy if one is set.
     * Falls back to a direct connection (logging at warn) when the proxy URL is missing or invalid.
     */
    fun open(urlStr: String): HttpURLConnection {
        val uri = URI.create(urlStr)
        val proxyStr = try {
            ResolverConfig.ytdlpProxy.trim()
        } catch (_: Exception) {
            ""
        }
        if (proxyStr.isEmpty()) return uri.toURL().openConnection() as HttpURLConnection
        val proxyUri = try {
            URI.create(proxyStr)
        } catch (_: Exception) {
            logger.warn("Invalid proxy URL: $proxyStr.")
            return uri.toURL().openConnection() as HttpURLConnection
        }
        val type = when (proxyUri.scheme?.lowercase()) {
            "socks5", "socks4", "socks" -> Proxy.Type.SOCKS
            else -> Proxy.Type.HTTP
        }
        val port = if (proxyUri.port > 0) proxyUri.port else if (type == Proxy.Type.SOCKS) 1080 else 8080
        val host = proxyUri.host ?: return uri.toURL().openConnection() as HttpURLConnection
        return uri.toURL().openConnection(Proxy(type, InetSocketAddress(host, port))) as HttpURLConnection
    }
}
