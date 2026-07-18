package com.dreamdisplays.util

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.*
import kotlinx.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * TTL-bounded LRU memoizer with in-flight request deduplication: concurrent callers for the same key
 * share one background load instead of spawning duplicate work, and fresh results are served from
 * memory until they expire.
 *
 * Centralizes the cache + `ConcurrentMap<K, Deferred>` + exception-unwrapping pattern that was
 * hand-rolled three times inside `yt-dlp` (format fetch, search, related).
 *
 * @param maxSize LRU capacity; least-recently-used entries beyond this are evicted.
 * @param ttlMs entry freshness window in milliseconds.
 * @param scope coroutine scope loads run in (e.g. [DreamCoroutines.clientIo]).
 * @param tag human-readable name used in error messages.
 */
class AsyncMemo<K : Any, V : Any>(
    maxSize: Int,
    private val ttlMs: Long,
    private val scope: CoroutineScope,
    private val tag: String,
) {
    private val cacheEnabled = maxSize > 0 && ttlMs > 0

    /** Fresh values. */
    private val cache: Cache<K, V> = Caffeine.newBuilder()
        .maximumSize(if (cacheEnabled) maxSize.toLong() else 0L)
        .expireAfterWrite(ttlMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        .build()

    /** Deferreds for in-flight loads. */
    private val inFlight: ConcurrentMap<K, Deferred<V>> = ConcurrentHashMap()

    /** Returns the cached value for [key] if present and younger than the TTL, else null. */
    fun peekFresh(key: K): V? = cache.getIfPresent(key)

    /** Inserts [value] for [key], refreshing its TTL. */
    fun put(key: K, value: V) {
        if (cacheEnabled) cache.put(key, value)
    }

    /** Drops [key] from both the cache and the in-flight map. */
    fun invalidate(key: K) {
        cache.invalidate(key)
        inFlight.remove(key)
    }

    /**
     * Starts (or joins) a background load of [key] via [loader] and returns its [Deferred]. The result
     * is cached on success; the in-flight slot is cleared when the load settles. Use for prefetching.
     */
    fun load(key: K, loader: suspend (K) -> V): Deferred<V> {
        peekFresh(key)?.let { return CompletableDeferred(it) }
        val deferred = inFlight.computeIfAbsent(key) { k ->
            scope.async {
                val value = loader(k)
                put(k, value)
                value
            }
        }
        deferred.invokeOnCompletion { inFlight.remove(key, deferred) }
        return deferred
    }

    /**
     * Returns the fresh cached value for [key] or blocks on a (shared) [loader] run. Waits at most
     * [timeoutSeconds] when positive, indefinitely otherwise. A timeout leaves the load running so a
     * later call can still pick up its result. Failures are unwrapped to [IOException].
     */
    @Throws(IOException::class)
    fun getBlocking(key: K, timeoutSeconds: Long = 0, loader: suspend (K) -> V): V {
        peekFresh(key)?.let { return it }
        val deferred = load(key, loader)
        return try {
            runBlocking {
                if (timeoutSeconds > 0) withTimeout(timeoutSeconds.seconds) { deferred.await() }
                else deferred.await()
            }
        } catch (e: Throwable) {
            throw (e as? IOException) ?: (e.cause as? IOException) ?: IOException("$tag failed for $key.", e)
        }
    }
}
