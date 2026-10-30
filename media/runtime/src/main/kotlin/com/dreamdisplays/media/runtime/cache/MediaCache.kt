package com.dreamdisplays.media.runtime.cache

import com.dreamdisplays.util.DreamCoroutines
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The one in-memory cache behind every media lookup. */
class MediaCache<K : Any, V : Any>(
    private val name: String,
    maxSize: Long,
    private val ttl: (V) -> Duration,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val inFlight = ConcurrentHashMap.newKeySet<K>()

    private val cache: Cache<K, V> = Caffeine.newBuilder()
        .maximumSize(maxSize)
        .expireAfter(object : Expiry<K, V> {
            override fun expireAfterCreate(key: K, value: V, currentTime: Long) = ttl(value).inWholeNanoseconds
            override fun expireAfterUpdate(key: K, value: V, currentTime: Long, currentDuration: Long) =
                ttl(value).inWholeNanoseconds

            override fun expireAfterRead(key: K, value: V, currentTime: Long, currentDuration: Long) = currentDuration
        })
        .build()

    /** @return the cached value for [key], or null when absent or expired. */
    fun get(key: K): V? = cache.getIfPresent(key)

    /** Stores [value] for [key], replacing any older entry. */
    fun put(key: K, value: V) = cache.put(key, value)

    /** Drops [key] so the next lookup fetches it again. */
    fun invalidate(key: K) = cache.invalidate(key)

    /** @return the cached value for [key], else runs [fetch] on this thread and stores what it returns. */
    fun getOrFetch(key: K, fetch: () -> V?): V? = get(key) ?: runCatching { fetch()?.also { put(key, it) } }
        .onFailure { logger.warn("{} fetch failed for {}: {}.", name, key, it.message) }
        .getOrNull()

    /**
     * Runs [fetch] for [key] in the background unless it is already cached or being fetched.
     *
     * Client-thread safe.
     */
    fun requestAsync(key: K, fetch: () -> V?) {
        if (get(key) != null || !inFlight.add(key)) return
        DreamCoroutines.clientIo.launch {
            try {
                getOrFetch(key, fetch)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    companion object {
        fun <V> liveAware(isLive: (V) -> Boolean, live: Duration = 60.seconds, static: Duration = 30.minutes): (V) -> Duration =
            { if (isLive(it)) live else static }

        fun <V> fixed(ttl: Duration): (V) -> Duration = { ttl }
    }
}
