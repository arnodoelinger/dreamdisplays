package com.dreamdisplays.media.source.platform

import com.dreamdisplays.util.DreamCoroutines
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * A reusable metadata cache for a single platform, keyed by a stable string.
 *
 * Factored out of what Twitch hand-rolled ([com.dreamdisplays.media.source.twitch.TwitchMetadataCache])
 * so Vimeo and Kick get the same behavior - async warm on the client thread, blocking resolve on a
 * background thread, live entries expiring quickly while VOD entries stay — from one class instead
 * of three copies. The platform supplies only the [name] (for logs) and the [fetch] function.
 *
 * @param liveTtlSeconds how long a live entry stays fresh; viewer counts and titles move fast.
 * @param staticTtlMinutes how long a VOD / on-demand entry stays fresh.
 * @param fetch blocking metadata lookup for a key; returns null when the video does not exist.
 *
 * @since 1.9.0
 */
class PlatformMetadataCache(
    private val name: String,
    private val liveTtlSeconds: Long,
    private val staticTtlMinutes: Long,
    private val fetch: (key: String) -> PlatformVideoMetadata?,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/${name}MetadataCache")

    private val cache: Cache<String, PlatformVideoMetadata> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfter(object : Expiry<String, PlatformVideoMetadata> {
            private fun ttlNanos(value: PlatformVideoMetadata) =
                if (value.isLive) TimeUnit.SECONDS.toNanos(liveTtlSeconds)
                else TimeUnit.MINUTES.toNanos(staticTtlMinutes)

            override fun expireAfterCreate(key: String, value: PlatformVideoMetadata, currentTime: Long) =
                ttlNanos(value)

            override fun expireAfterUpdate(
                key: String, value: PlatformVideoMetadata, currentTime: Long, currentDuration: Long,
            ) = ttlNanos(value)

            override fun expireAfterRead(
                key: String, value: PlatformVideoMetadata, currentTime: Long, currentDuration: Long,
            ) = currentDuration
        })
        .build()

    private val inFlight: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(2, TimeUnit.MINUTES)
        .build()

    /** Returns the cached metadata for [key], or null when not yet fetched. */
    fun get(key: String): PlatformVideoMetadata? = cache.getIfPresent(key)

    /** Seeds [metadata] for [key]; used by the resolver, which fetches it during playback anyway. */
    fun put(key: String, metadata: PlatformVideoMetadata) = cache.put(key, metadata)

    /** Drops [key], so the next lookup re-fetches instead of serving a stale live entry. */
    fun invalidate(key: String) = cache.invalidate(key)

    /** Warms [key] in the background if it is not already cached or in flight (client-thread safe). */
    fun requestAsync(key: String) {
        if (cache.getIfPresent(key) != null) return
        if (inFlight.asMap().putIfAbsent(key, true) != null) return
        DreamCoroutines.clientIo.launch { fetchAndStore(key) }
    }

    /**
     * Returns the cached metadata for [key], else fetches it (blocking) and stores it. For callers
     * already on a background thread (a search request) that need the value now, not just a warm.
     */
    fun resolveBlocking(key: String): PlatformVideoMetadata? {
        get(key)?.let { return it }
        return runCatching { fetch(key)?.also { cache.put(key, it) } }
            .onFailure { logger.warn("{} metadata fetch failed for {}: {}.", name, key, it.message) }
            .getOrNull()
    }

    private fun fetchAndStore(key: String) {
        runCatching { fetch(key) }
            .onSuccess { metadata -> metadata?.let { cache.put(key, it) } }
            .onFailure { logger.warn("{} metadata fetch failed for {}: {}.", name, key, it.message) }
            .also { inFlight.invalidate(key) }
    }
}
