package com.dreamdisplays.media

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetAddress
import java.net.URI

/**
 * SSRF guard for client-supplied media URLs.
 */
object MediaHostGuard {
    private val logger = LoggerFactory.getLogger("DreamDisplays/MediaHostGuard")

    /** Escape hatch for operators who intentionally host media on a private network. */
    private val allowPrivate: Boolean =
        System.getProperty("dreamdisplays.allowPrivateUrls", "false").toBoolean()

    /**
     * Returns true when [url] is safe to fetch: the guard is disabled, or the URL's host resolves
     * exclusively to public unicast addresses. Returns false on any non-public address or when the
     * host cannot be parsed or resolved.
     */
    fun isAllowed(url: String): Boolean {
        if (allowPrivate) return true
        val host = hostOf(url) ?: run {
            logger.warn("Blocked media URL with no parseable host: ${url.take(120)}")
            return false
        }
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            logger.warn("Blocked media URL; host '$host' did not resolve: ${e.message}.")
            return false
        }
        addresses.firstOrNull { isNonPublic(it) }?.let {
            logger.warn("Blocked media URL resolving to non-public address ${it.hostAddress} (host=$host).")
            return false
        }
        return true
    }

    /** Like [isAllowed] but throws [IOException] when blocked, so it slots into the playback launch paths. */
    @Throws(IOException::class)
    fun requireAllowed(url: String) {
        if (!isAllowed(url)) throw IOException("Refusing to open a media URL on a non-public host.")
    }

    /** Extracts the host from [url] (stripping IPv6 literal brackets), or null when it cannot be parsed. */
    private fun hostOf(url: String): String? =
        try {
            URI(url.trim()).host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }

    /** True when [addr] is anything other than a public unicast address. */
    private fun isNonPublic(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress
        ) return true
        // IPv6 unique-local (fc00::/7) is not covered by isSiteLocalAddress
        val bytes = addr.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }
}
