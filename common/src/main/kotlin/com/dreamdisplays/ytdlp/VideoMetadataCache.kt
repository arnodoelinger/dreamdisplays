package com.dreamdisplays.ytdlp

import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.media.api.MediaSearchResult
import com.dreamdisplays.media.api.MediaSearchService
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** In-memory cache of [MediaSearchResult] keyed by YouTube video ID. */
object VideoMetadataCache {
    private val logger = LoggerFactory.getLogger("DreamDisplays/VideoMetadataCache")
    private val CACHE = ConcurrentHashMap<String, MediaSearchResult>()
    private val IN_FLIGHT = ConcurrentHashMap<String, Boolean>()
    private val EXEC = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DD-VideoMeta").apply { isDaemon = true }
    }

    /** Stores [info] in the cache under [videoId] and also updates [VideoTitleCache]. */
    fun put(videoId: String, info: MediaSearchResult) {
        if (videoId.isEmpty()) return
        CACHE[videoId] = info
        VideoTitleCache.put(videoId, info.title)
    }

    /** Returns the cached [MediaSearchResult] for [videoId], or null if not yet fetched. */
    fun get(videoId: String): MediaSearchResult? = CACHE[videoId]

    /** Fetches and caches metadata for [videoId] in the background if it is not already cached or in flight. */
    fun requestAsync(videoId: String) {
        if (videoId.isEmpty()) return
        if (CACHE.containsKey(videoId)) return
        if (IN_FLIGHT.putIfAbsent(videoId, true) != null) return
        EXEC.submit { fetchAndStore(videoId) }
    }

    /** Uses the registry [MediaSearchService] to fetch metadata for [videoId] and stores the result. */
    private fun fetchAndStore(videoId: String) {
        try {
            DreamServices.registry.getOrNull<MediaSearchService>()?.metadata(videoId)?.let { put(videoId, it) }
        } catch (e: Exception) {
            logger.warn("Metadata fetch failed for $videoId: ${e.message}")
        } finally {
            IN_FLIGHT.remove(videoId)
        }
    }
}
