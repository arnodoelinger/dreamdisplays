package com.dreamdisplays.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
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
    private class Entry<V>(val value: V, val createdAtMs: Long)

    private val cache: MutableMap<K, Entry<V>> =
        Collections.synchronizedMap(object : LinkedHashMap<K, Entry<V>>(maxSize + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<K, Entry<V>>) = size > maxSize
        })
    private val inFlight: ConcurrentMap<K, Deferred<V>> = ConcurrentHashMap()

    /** Returns the cached value for [key] if present and younger than the TTL, else null. */
    fun peekFresh(key: K): V? {
        val e = cache[key] ?: return null
        return if (System.currentTimeMillis() - e.createdAtMs <= ttlMs) e.value else null
    }

    /** Inserts [value] for [key], refreshing its TTL. */
    fun put(key: K, value: V) {
        cache[key] = Entry(value, System.currentTimeMillis())
    }

    /** Drops [key] from both the cache and the in-flight map. */
    fun invalidate(key: K) {
        cache.remove(key)
        inFlight.remove(key)
    }

    /**
     * Starts (or joins) a background load of [key] via [loader] and returns its [Deferred]. The result
     * is cached on success; the in-flight slot is cleared when the load settles. Use for prefetching.
     */
    fun load(key: K, loader: suspend (K) -> V): Deferred<V> {
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
            throw (e as? IOException) ?: (e.cause as? IOException) ?: IOException("$tag failed for $key", e)
        }
    }
}
