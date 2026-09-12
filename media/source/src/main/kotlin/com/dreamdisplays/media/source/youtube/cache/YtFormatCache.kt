package com.dreamdisplays.media.source.youtube.cache

import com.dreamdisplays.media.source.youtube.model.YtStream
import com.dreamdisplays.media.source.youtube.model.YtStreams
import com.dreamdisplays.util.AsyncMemo
import com.dreamdisplays.util.DreamCoroutines
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import org.slf4j.LoggerFactory

/**
 * In-memory -> disk -> [resolve] cache for resolved YouTube stream lists, with separate staleness
 * rules for live (short-lived tokens) and partial ("walled") results (rechecked on a cadence in
 * case YouTube's PO-token wall lifts). Extracted from [com.dreamdisplays.media.source.youtube.YtDlp]
 * to isolate caching from the `NewPipeExtractor` / `yt-dlp` resolution strategy itself.
 */
class YtFormatCache(private val resolve: suspend (String) -> List<YtStream>) {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Shared 5h format-cache TTL: the on-disk default and this cache's in-memory format TTL. */
    private val cacheTtlMs: Long = FormatDiskCache.DEFAULT_TTL_MS

    /** Format cache: in-memory -> disk -> [resolve]. 5h TTL, 200 entries. */
    private val formatMemo = AsyncMemo<String, List<YtStream>>(200, cacheTtlMs, DreamCoroutines.clientIo, "fetch")

    /** When each URL's streams were resolved (for live TTL refresh). */
    private val fetchedAtMs: Cache<String, Long> = Caffeine.newBuilder().maximumSize(300).build()

    /** Returns stream list for videoUrl (in-memory -> disk -> [resolve]). */
    @Throws(IOException::class)
    fun fetch(videoUrl: String): List<YtStream> {
        var streams = loadFromDisk(videoUrl) ?: formatMemo.getBlocking(videoUrl) { fetchAndPersist(it) }
        if (isStaleLive(videoUrl, streams) || isStalePartial(videoUrl, streams)) {
            invalidate(videoUrl)
            streams = formatMemo.getBlocking(videoUrl) { fetchAndPersist(it) }
        }
        return streams
    }

    /** Fires a background fetch for [videoUrl] if not already cached, so it is ready before [fetch] is called. */
    fun prefetch(videoUrl: String) {
        val cached = formatMemo.peekFresh(videoUrl) ?: loadFromDisk(videoUrl)
        if (cached != null && !isStaleLive(videoUrl, cached) && !isStalePartial(videoUrl, cached)) return
        if (cached != null) invalidate(videoUrl)
        formatMemo.load(videoUrl) { fetchAndPersist(it) }.invokeOnCompletion { e ->
            if (e != null && e !is CancellationException) {
                logger.debug("Prefetch failed for {}: {}", videoUrl, e.message)
            }
        }
    }

    /** Removes [videoUrl] from the in-memory format cache, in-flight map, and disk cache. */
    fun invalidate(videoUrl: String) {
        formatMemo.invalidate(videoUrl)
        fetchedAtMs.invalidate(videoUrl)
        FormatDiskCache.deleteEntry(videoUrl)
    }

    /** Whether cached live result is stale (playlist URLs have expiring tokens). */
    private fun isStaleLive(videoUrl: String, streams: List<YtStream>): Boolean {
        if (streams.none { it.isLive }) return false
        val at = fetchedAtMs.getIfPresent(videoUrl) ?: return true
        return System.currentTimeMillis() - at > FormatDiskCache.LIVE_TTL_MS
    }

    /** Whether cached partial result is stale (re-check PO-token wall on cadence). */
    private fun isStalePartial(videoUrl: String, streams: List<YtStream>): Boolean {
        if (streams.isEmpty() || offersFullResult(streams)) return false
        val at = fetchedAtMs.getIfPresent(videoUrl) ?: return true
        return System.currentTimeMillis() - at > FormatDiskCache.PARTIAL_TTL_MS
    }

    /** Whether streams is worth caching at full TTL (quality ladder or live). */
    private fun offersFullResult(streams: List<YtStream>): Boolean =
        streams.any { it.isLive } || YtStreams.offersQualityLadder(streams)

    /** Returns the disk-cached streams for [videoUrl] if fresh, promoting them into the in-memory cache. */
    private fun loadFromDisk(videoUrl: String): List<YtStream>? {
        formatMemo.peekFresh(videoUrl)?.let { return it }
        val fromDisk = FormatDiskCache.load(videoUrl, cacheTtlMs)?.takeIf { it.isNotEmpty() } ?: return null
        val immutable = fromDisk.toList()
        formatMemo.put(videoUrl, immutable)
        fetchedAtMs.put(videoUrl, System.currentTimeMillis())
        return immutable
    }

    /** Resolves streams via [resolve] and mirrors to disk cache (partial results persisted too). */
    @Throws(IOException::class)
    private suspend fun fetchAndPersist(videoUrl: String): List<YtStream> {
        val streams = resolve(videoUrl).toList()
        fetchedAtMs.put(videoUrl, System.currentTimeMillis())
        if (streams.isNotEmpty()) FormatDiskCache.saveAsync(videoUrl, streams)
        return streams
    }
}
