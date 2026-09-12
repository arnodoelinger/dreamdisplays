package com.dreamdisplays.media.runtime.security

import com.dreamdisplays.util.net.DreamHttpClient
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * SSRF guard for client-supplied media URLs. Both entry points are memoized, because they sit on the playback launch path
 * and get called repeatedly.
 */
object MediaHostGuard {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Escape hatch for operators who intentionally host media on a private network. */
    private val allowPrivate: Boolean =
        System.getProperty("dreamdisplays.allowPrivateUrls", "false").toBoolean()

    /**
     * How long a host's verdict is reused. Deliberately the same order as the JDK's own positive DNS
     * cache (30 s by default), so memorizing here widens no rebinding window that resolving the host
     * again would have closed anyway — the second lookup would have been served from that cache.
     */
    private const val HOST_VERDICT_TTL_SECONDS = 30L

    /**
     * How long a *redirecting* URL's final destination is reused. Short, because that destination is
     * often a signed, expiring link; long enough to cover one launch's repeated calls and the seeks
     * that follow it.
     */
    private const val REDIRECTED_TTL_NANOS = 60L * 1_000_000_000L

    /**
     * How long a URL known not to redirect is remembered. There is nothing to go stale — the
     * mapping is the identity — so this only has to stay under the lifetime of the CDN URLs
     * themselves, and it removes the probe from every restart within one viewing session.
     */
    private const val IDENTITY_TTL_NANOS = 5L * 60L * 1_000_000_000L

    /** Per-host public / non-public verdict; also collapses concurrent lookups for the same host. */
    private val hostVerdicts: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(512)
        .expireAfterWrite(HOST_VERDICT_TTL_SECONDS, TimeUnit.SECONDS)
        .build()

    /** A walked redirect chain: the URL handed to the media stack, plus how long it may be reused. */
    private class ResolvedUrl(@JvmField val url: String, @JvmField val ttlNanos: Long)

    private val resolvedUrls: Cache<String, ResolvedUrl> = Caffeine.newBuilder()
        .maximumSize(256)
        .expireAfter(object : Expiry<String, ResolvedUrl> {
            override fun expireAfterCreate(key: String, value: ResolvedUrl, currentTime: Long): Long =
                value.ttlNanos

            override fun expireAfterUpdate(
                key: String, value: ResolvedUrl, currentTime: Long, currentDuration: Long,
            ): Long = value.ttlNanos

            override fun expireAfterRead(
                key: String, value: ResolvedUrl, currentTime: Long, currentDuration: Long,
            ): Long = currentDuration
        })
        .build()

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
        return hostVerdicts.get(host, ::resolvesToPublicAddresses)
    }

    /** Uncached host check: resolves [host] and rejects it if any address is not public unicast. */
    private fun resolvesToPublicAddresses(host: String): Boolean {
        val addresses = runCatching {
            InetAddress.getAllByName(host)
        }.onFailure { e ->
            logger.warn("Blocked media URL; host '$host' did not resolve: ${e.message}.")
        }.getOrNull() ?: return false
        addresses.firstOrNull { isNonPublic(it) }?.let {
            logger.warn("Blocked media URL resolving to non-public address ${it.hostAddress} (host=$host).")
            return false
        }
        return true
    }

    /** Forgets what is known about [url] and its host, so the next check probes for real. */
    fun invalidate(url: String) {
        resolvedUrls.invalidate(url)
        hostOf(url)?.let { hostVerdicts.invalidate(it) }
    }

    /** Like [isAllowed] but throws [IOException] when blocked, so it slots into the playback launch paths. */
    @Throws(IOException::class)
    fun requireAllowed(url: String) {
        if (!isAllowed(url)) throw IOException("Refusing to open a media URL on a non-public host.")
    }

    /**
     * Validates [url] and, for `http(s)` URLs, walks its redirect chain ourselves (each hop re-checked with [requireAllowed])
     * up to [maxRedirects], returning the final safe URL.
     */
    @Throws(IOException::class)
    fun resolveSafeUrl(url: String, maxRedirects: Int = 5): String {
        requireAllowed(url)
        if (allowPrivate) return url
        val scheme = runCatching { URI(url.trim()).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") return url

        resolvedUrls.getIfPresent(url)?.let { cached ->
            requireAllowed(cached.url)
            return cached.url
        }

        var current = url
        var hops = 0
        while (hops < maxRedirects) {
            val location = peekRedirectLocation(current)
            if (location == null) {
                resolvedUrls.put(
                    url,
                    ResolvedUrl(current, if (hops == 0) IDENTITY_TTL_NANOS else REDIRECTED_TTL_NANOS),
                )
                return current
            }
            val next = runCatching {
                URI(current.trim()).resolve(location.trim()).toString()
            }.getOrElse { e ->
                throw IOException("Refusing to follow an unparseable media redirect: ${e.message}.")
            }
            requireAllowed(next)
            current = next
            hops++
        }
        throw IOException("Refusing to follow more than $maxRedirects media redirects.")
    }

    /**
     * Issues a redirect-less probe against [url] and returns its `Location` header, or null when
     * the response is not a redirect (including when the probe itself fails, so a server that
     * rejects the probe degrades to "trust the URL as-is" rather than blocking playback).
     */
    private fun peekRedirectLocation(url: String): String? {
        return runCatching {
            DreamHttpClient.peekRedirectLocation(
                url.trim(),
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf("Range" to "bytes=0-0"),
                    connectTimeoutMs = 5_000,
                    readTimeoutMs = 5_000,
                ),
            )
        }.onFailure { e ->
            logger.debug("Redirect probe failed for ${url.take(120)}: ${e.message}.")
        }.getOrNull()
    }

    /** Extracts the host from [url] (stripping IPv6 literal brackets), or null when it cannot be parsed. */
    private fun hostOf(url: String): String? =
        runCatching {
            URI(url.trim()).host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() }
        }.getOrNull()

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
        return o0 == 0 ||                                // 0.0.0.0/8 "this network" (only 0.0.0.0 is isAnyLocal)
                (o0 == 100 && o1 in 64..127) ||    // 100.64.0.0/10 carrier-grade NAT (RFC 6598)
                o0 >= 240                                // 240.0.0.0/4 reserved + 255.255.255.255 broadcast
    }

    /** Non-public IPv6: unique-local fc00::/7, plus IPv4-mapped addresses re-checked as IPv4. */
    private fun isNonPublicV6(bytes: ByteArray): Boolean {
        if ((bytes[0].toInt() and 0xfe) == 0xfc) return true // fc00::/7 unique-local
        // IPv4-mapped (::ffff:a.b.c.d): the flag checks above don't unwrap it, so re-check the IPv4
        val isMappedV4 = (0..9).all { bytes[it].toInt() == 0 } &&
                bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        return isMappedV4 && isNonPublicV4(bytes.copyOfRange(12, 16))
    }
}
