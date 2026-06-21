package com.dreamdisplays.media.source.ytdlp

import com.dreamdisplays.media.api.MediaSearchResult
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** In-memory cache of [MediaSearchResult] keyed by YouTube video ID. */
object VideoMetadataCache {
    private val logger = LoggerFactory.getLogger("DreamDisplays/VideoMetadataCache")
    private val CACHE = ConcurrentHashMap<String, MediaSearchResult>()
    private val IN_FLIGHT = ConcurrentHashMap<String, Boolean>()

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
        DreamCoroutines.clientIo.launch { fetchAndStore(videoId) }
    }

    /** Fetches metadata for [videoId] via [YouTubeInnerTube] and stores the result. */
    private fun fetchAndStore(videoId: String) {
        try {
            YouTubeInnerTube.metadata(videoId)?.let { put(videoId, it) }
        } catch (e: Exception) {
            logger.warn("Metadata fetch failed for $videoId: ${e.message}")
        } finally {
            IN_FLIGHT.remove(videoId)
        }
    }
}
