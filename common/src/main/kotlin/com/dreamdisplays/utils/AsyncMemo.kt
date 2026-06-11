package com.dreamdisplays.utils

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * TTL-bounded LRU memoizer with in-flight request deduplication: concurrent callers for the same key
 * share one background load instead of spawning duplicate work, and fresh results are served from
 * memory until they expire.
 *
 * Centralizes the cache + `ConcurrentMap<K, CompletableFuture>` + exception-unwrapping pattern that
 * was hand-rolled three times inside `yt-dlp` (format fetch, search, related).
 *
 * @param maxSize LRU capacity; least-recently-used entries beyond this are evicted.
 * @param ttlMs entry freshness window in milliseconds.
 * @param executor where loads run.
 * @param tag human-readable name used in error messages.
 *
 * @since 1.7.0
 */
class AsyncMemo<K : Any, V : Any>(
    maxSize: Int,
    private val ttlMs: Long,
    private val executor: Executor,
    private val tag: String,
) {
    private class Entry<V>(val value: V, val createdAtMs: Long)

    private val cache: MutableMap<K, Entry<V>> =
        Collections.synchronizedMap(object : LinkedHashMap<K, Entry<V>>(maxSize + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<K, Entry<V>>) = size > maxSize
        })
    private val inFlight: ConcurrentMap<K, CompletableFuture<V>> = ConcurrentHashMap()

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
     * Starts (or joins) a background load of [key] via [loader] and returns its future. The result is
     * cached on success; the in-flight slot is cleared when the future settles. Use for prefetching.
     */
    fun load(key: K, loader: (K) -> V): CompletableFuture<V> {
        val future = inFlight.computeIfAbsent(key) { k ->
            CompletableFuture.supplyAsync({
                try {
                    val value = loader(k)
                    put(k, value)
                    value
                } catch (e: Exception) {
                    throw e as? CompletionException ?: CompletionException(e)
                }
            }, executor)
        }
        future.whenComplete { _, _ -> inFlight.remove(key, future) }
        return future
    }

    /**
     * Returns the fresh cached value for [key] or blocks on a (shared) [loader] run. Waits at most
     * [timeoutSeconds] when positive, indefinitely otherwise. Failures are unwrapped to [IOException].
     */
    @Throws(IOException::class)
    fun getBlocking(key: K, timeoutSeconds: Long = 0, loader: (K) -> V): V {
        peekFresh(key)?.let { return it }
        val future = load(key, loader)
        try {
            return if (timeoutSeconds > 0) future.get(timeoutSeconds, TimeUnit.SECONDS) else future.get()
        } catch (e: Exception) {
            throw (e.cause as? IOException) ?: IOException("$tag failed for $key", e.cause ?: e)
        }
    }
}
