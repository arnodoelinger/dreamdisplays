package com.dreamdisplays.media.source.youtube.search

import com.dreamdisplays.api.media.search.model.MediaSearchPage
import com.dreamdisplays.api.media.search.model.MediaSearchResult
import com.dreamdisplays.api.media.search.model.SortOrder
import com.dreamdisplays.api.media.source.url.YouTubeUrls
import com.dreamdisplays.media.source.youtube.YouTubeInnerTube
import com.dreamdisplays.util.AsyncMemo
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.io.IOException
import java.util.Locale

/**
 * Search / related-video lookups against [YouTubeInnerTube], memoized so repeated menu renders
 * don't re-hit the network. Extracted from [com.dreamdisplays.media.source.youtube.YtDlp] to
 * isolate search caching from format resolution.
 */
object YtDlpSearchCache {
    /** Info cache TTL: 30 minutes is a good balance between freshness and avoiding repeated InnerTube calls. */
    private const val INFO_CACHE_TTL_MS: Long = 30L * 60L * 1_000L

    /** In-memory cache of search results, keyed by lowercase query + limit. */
    private val searchMemo = AsyncMemo<String, List<MediaSearchResult>>(100, INFO_CACHE_TTL_MS, DreamCoroutines.clientIo, "search")
    private val relatedMemo = AsyncMemo<String, List<MediaSearchResult>>(200, INFO_CACHE_TTL_MS, DreamCoroutines.clientIo, "related")

    /** Searches YouTube for [query] via InnerTube, returning up to [limit] results; uses a 30-minute in-memory cache. */
    @Throws(IOException::class)
    fun search(query: String, limit: Int): List<MediaSearchResult> {
        if (query.isBlank()) return ArrayList()
        val n = limit.coerceIn(1, 25)
        val key = query.trim().lowercase(Locale.ENGLISH) + "|" + n
        return searchMemo.getBlocking(key, timeoutSeconds = 30) {
            YouTubeInnerTube.search(query.trim(), n).toList()
        }
    }

    /** Fetches up to [limit] related videos for [videoId] via InnerTube; falls back to title search if none found. */
    @Throws(IOException::class)
    fun related(videoId: String, limit: Int): List<MediaSearchResult> {
        if (videoId.isBlank()) return ArrayList()
        val n = limit.coerceIn(1, 25)
        return relatedMemo.getBlocking("$videoId|$n", timeoutSeconds = 30) {
            val nextResult = YouTubeInnerTube.next(videoId)
            var hits = ArrayList(nextResult.related)
            hits.removeAll { it.id == videoId }
            // If no related found, fall back to searching by title
            if (hits.isEmpty() && !nextResult.title.isNullOrBlank()) {
                hits = ArrayList(YouTubeInnerTube.search(nextResult.title, n + 2))
                hits.removeAll { it.id == videoId }
            }
            if (hits.size > n) hits = ArrayList(hits.subList(0, n))
            hits.toList()
        }
    }

    /** Fetches the first page (up to [limit] results) matching [query] in [sortOrder]; a fresh network call each time (continuation isn't cacheable). */
    @Throws(IOException::class)
    fun searchPage(query: String, limit: Int, sortOrder: SortOrder = SortOrder.RELEVANCE): MediaSearchPage {
        if (query.isBlank()) return MediaSearchPage(emptyList(), null)
        return YouTubeInnerTube.searchPage(query.trim(), limit.coerceIn(1, 25), sortOrder)
    }

    /** Fetches the page following [continuationToken] from a prior [searchPage]/[searchMore] call. */
    @Throws(IOException::class)
    fun searchMore(continuationToken: String, limit: Int): MediaSearchPage =
        YouTubeInnerTube.searchMore(continuationToken, limit.coerceIn(1, 25))

    /** Fetches the first page (up to [limit] results) related to [videoId]. */
    @Throws(IOException::class)
    fun relatedPage(videoId: String, limit: Int): MediaSearchPage {
        if (videoId.isBlank()) return MediaSearchPage(emptyList(), null)
        return YouTubeInnerTube.relatedPage(videoId, limit.coerceIn(1, 25))
    }

    /** Fetches the page following [continuationToken] from a prior [relatedPage]/[relatedMore] call. */
    @Throws(IOException::class)
    fun relatedMore(continuationToken: String, limit: Int): MediaSearchPage =
        YouTubeInnerTube.relatedMore(continuationToken, limit.coerceIn(1, 25))

    /** Extracts the 11-character YouTube video ID from a full URL, short URL, or bare ID. Returns null if not recognized. */
    fun extractVideoId(url: String?): String? = YouTubeUrls.extractVideoId(url)
}
