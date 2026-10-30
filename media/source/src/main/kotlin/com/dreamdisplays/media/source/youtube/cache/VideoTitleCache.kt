package com.dreamdisplays.media.source.youtube.cache

import com.dreamdisplays.media.runtime.cache.MediaCache
import kotlin.time.Duration.Companion.minutes

/** Cache for video titles, to avoid repeated calls to `yt-dlp`. */
object VideoTitleCache {
    private val cache = MediaCache<String, String>("YouTube titles", 1_000, MediaCache.fixed(30.minutes))

    /** Stores [title] in the cache under [videoId]. */
    fun put(videoId: String, title: String) {
        if (videoId.isNotEmpty() && title.isNotEmpty()) cache.put(videoId, title)
    }

    /** Returns the cached title for [videoId], or null if not yet fetched. */
    fun get(videoId: String): String? = cache.get(videoId)
}
