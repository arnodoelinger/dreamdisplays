package com.dreamdisplays.media

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
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

    /**
     * Validates [url] and, for `http(s)` URLs, walks its redirect chain ourselves (each hop
     * re-checked with [requireAllowed]), returning the final URL to hand to `FFmpeg` / `libav`.
     *
     * [isAllowed]/[requireAllowed] alone only validate the URL we were given; `FFmpeg` and the
     * in-process `libav` path resolve DNS and follow redirects entirely on their own, moments
     * later and outside this guard's view.
     */
    @Throws(IOException::class)
    fun resolveSafeUrl(url: String, maxRedirects: Int = 5): String {
        requireAllowed(url)
        if (allowPrivate) return url
        val scheme = try { URI(url.trim()).scheme?.lowercase() } catch (_: Exception) { null }
        if (scheme != "http" && scheme != "https") return url

        var current = url
        repeat(maxRedirects) {
            val location = peekRedirectLocation(current) ?: return current
            val next = try {
                URI(current.trim()).resolve(location.trim()).toString()
            } catch (e: Exception) {
                throw IOException("Refusing to follow an unparseable media redirect: ${e.message}.")
            }
            requireAllowed(next)
            current = next
        }
        throw IOException("Refusing to follow more than $maxRedirects media redirects.")
    }

    /**
     * Issues a redirect-less `HEAD` probe against [url] and returns its `Location` header, or
     * null when the response is not a redirect (including when the probe itself fails, so a
     * server that rejects `HEAD` degrades to "trust the URL as-is" rather than blocking playback).
     */
    private fun peekRedirectLocation(url: String): String? {
        val conn = try {
            URI(url.trim()).toURL().openConnection() as? HttpURLConnection
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            if (conn.responseCode in 300..399) conn.getHeaderField("Location") else null
        } catch (e: Exception) {
            logger.debug("Redirect probe failed for ${url.take(120)}: ${e.message}.")
            null
        } finally {
            conn.disconnect()
        }
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
        // Loopback (127/8, ::1), link-local (169.254/16, fe80::/10), site-local (10/8, 172.16/12,
        // 192.168/16), wildcard (0.0.0.0, ::) and multicast are all handled by the platform flags.
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress
        ) return true
        val bytes = addr.address
        return when (bytes.size) {
            4 -> isNonPublicV4(bytes)
            16 -> isNonPublicV6(bytes)
            else -> false
        }
    }

    /** Non-public IPv4 ranges the [InetAddress] flag checks miss (CGNAT, "this network", reserved). */
    private fun isNonPublicV4(bytes: ByteArray): Boolean {
        val o0 = bytes[0].toInt() and 0xff
        val o1 = bytes[1].toInt() and 0xff
        return o0 == 0 ||                      // 0.0.0.0/8 "this network" (only 0.0.0.0 is isAnyLocal)
            (o0 == 100 && o1 in 64..127) ||    // 100.64.0.0/10 carrier-grade NAT (RFC 6598)
            o0 >= 240                          // 240.0.0.0/4 reserved + 255.255.255.255 broadcast
    }

    /** Non-public IPv6: unique-local fc00::/7, plus IPv4-mapped addresses re-checked as IPv4. */
    private fun isNonPublicV6(bytes: ByteArray): Boolean {
        if ((bytes[0].toInt() and 0xfe) == 0xfc) return true // fc00::/7 unique-local
        // IPv4-mapped (::ffff:a.b.c.d): the flag checks above don't unwrap it, so re-check the IPv4.
        val isMappedV4 = (0..9).all { bytes[it].toInt() == 0 } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        return isMappedV4 && isNonPublicV4(bytes.copyOfRange(12, 16))
    }
}
