package com.dreamdisplays.media.source.youtube.cache

import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.media.runtime.cache.MediaCache
import com.dreamdisplays.media.source.youtube.YouTubeInnerTube
import kotlin.time.Duration.Companion.minutes

/** In-memory cache of [MediaSearchResult] keyed by YouTube video ID. */
object VideoMetadataCache {
    private val cache = MediaCache<String, MediaSearchResult>("YouTube metadata", 500, MediaCache.fixed(30.minutes))

    /** Stores [info] in the cache under [videoId] and also updates [VideoTitleCache]. */
    fun put(videoId: String, info: MediaSearchResult) {
        if (videoId.isEmpty()) return
        cache.put(videoId, info)
        VideoTitleCache.put(videoId, info.title)
    }

    /** Returns the cached [MediaSearchResult] for [videoId], or null if not yet fetched. */
    fun get(videoId: String): MediaSearchResult? = cache.get(videoId)

    /** Fetches and caches metadata for [videoId] in the background if it is not already cached or in flight. */
    fun requestAsync(videoId: String) {
        if (videoId.isEmpty()) return
        cache.requestAsync(videoId) { YouTubeInnerTube.metadata(videoId)?.also { VideoTitleCache.put(videoId, it.title) } }
    }
}
