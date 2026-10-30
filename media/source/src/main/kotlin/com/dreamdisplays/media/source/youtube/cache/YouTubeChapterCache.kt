package com.dreamdisplays.media.source.youtube.cache

import com.dreamdisplays.api.media.search.MediaChapter
import com.dreamdisplays.media.runtime.cache.MediaCache
import com.dreamdisplays.media.source.youtube.YouTubeInnerTube
import kotlin.time.Duration.Companion.minutes

/**
 * In-memory cache of YouTube chapter markers keyed by video id. Deliberately separate from
 * [VideoMetadataCache]: that one is also filled with search-result stubs, which carry no chapters
 * and would then block any fetch that could supply them.
 */
object YouTubeChapterCache {
    private val cache = MediaCache<String, List<MediaChapter>>("YouTube chapters", 200, MediaCache.fixed(30.minutes))

    /** Chapters for [videoId], or null while they have not been fetched yet. */
    fun get(videoId: String): List<MediaChapter>? = cache.get(videoId)

    /** Fetches chapters for [videoId] in the background unless they are cached or already in flight. */
    fun requestAsync(videoId: String) {
        if (videoId.isNotEmpty()) cache.requestAsync(videoId) { YouTubeInnerTube.chapters(videoId) }
    }
}
