package com.dreamdisplays.media.source.twitch

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * In-memory cache of [TwitchMetadata], keyed by [cacheKey] (channel / VOD-id / clip-slug, so the
 * three kinds never collide). Live lookups expire quickly since viewer count and title change
 * continuously; VOD / clip lookups are static and share [VideoMetadataCache]'s longer TTL.
 */
object TwitchMetadataCache {
    private val logger = LoggerFactory.getLogger("DreamDisplays/TwitchMetadataCache")

    private const val LIVE_TTL_SECONDS = 60L
    private const val VOD_TTL_MINUTES = 30L
    private const val IN_FLIGHT_TTL_MINUTES = 2L

    private val CACHE: Cache<String, TwitchMetadata> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfter(object : Expiry<String, TwitchMetadata> {
            private fun ttlNanos(value: TwitchMetadata) =
                if (value.isLive) TimeUnit.SECONDS.toNanos(LIVE_TTL_SECONDS)
                else TimeUnit.MINUTES.toNanos(VOD_TTL_MINUTES)

            override fun expireAfterCreate(key: String, value: TwitchMetadata, currentTime: Long) = ttlNanos(value)
            override fun expireAfterUpdate(
                key: String, value: TwitchMetadata, currentTime: Long, currentDuration: Long,
            ) = ttlNanos(value)

            override fun expireAfterRead(
                key: String, value: TwitchMetadata, currentTime: Long, currentDuration: Long,
            ) = currentDuration
        })
        .build()

    private val IN_FLIGHT: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(IN_FLIGHT_TTL_MINUTES, TimeUnit.MINUTES)
        .build()

    /** The cache key for [source]: distinguishes channel / VOD / clip lookups from one another. */
    fun cacheKey(source: MediaSource.Twitch): String? = when {
        source.channel != null -> "channel:${source.channel}"
        source.videoId != null -> "video:${source.videoId}"
        source.clipSlug != null -> "clip:${source.clipSlug}"
        else -> null
    }

    /** Returns the cached [TwitchMetadata] for [key], or null if not yet fetched. */
    fun get(key: String): TwitchMetadata? = CACHE.getIfPresent(key)

    /** Seeds [metadata] for [source]; used by callers that already fetched it (e.g. [TwitchResolver]). */
    fun put(source: MediaSource.Twitch, metadata: TwitchMetadata) {
        cacheKey(source)?.let { CACHE.put(it, metadata) }
    }

    /** Fetches and caches metadata for [source] in the background if not already cached or in flight. */
    fun requestAsync(source: MediaSource.Twitch) {
        val key = cacheKey(source) ?: return
        if (CACHE.getIfPresent(key) != null) return
        if (IN_FLIGHT.asMap().putIfAbsent(key, true) != null) return
        DreamCoroutines.clientIo.launch { fetchAndStore(key, source) }
    }

    /**
     * Returns the cached metadata for [source] if present, else fetches it (blocking) via
     * [TwitchApi] and stores the result. For callers already on a background thread (e.g. a search
     * request) that need the value immediately, rather than just warming the cache.
     */
    fun resolveBlocking(source: MediaSource.Twitch): TwitchMetadata? {
        val key = cacheKey(source) ?: return null
        get(key)?.let { return it }
        return try {
            TwitchApi.resolve(source)?.also { CACHE.put(key, it) }
        } catch (e: Exception) {
            logger.warn("Twitch metadata fetch failed for {}: {}.", key, e.message)
            null
        }
    }

    private fun fetchAndStore(key: String, source: MediaSource.Twitch) {
        try {
            TwitchApi.resolve(source)?.let { CACHE.put(key, it) }
        } catch (e: Exception) {
            logger.warn("Twitch metadata fetch failed for {}: {}.", key, e.message)
        } finally {
            IN_FLIGHT.invalidate(key)
        }
    }
}
