package com.dreamdisplays.api.media.search

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Contract for YouTube search, related-video lookup, and video-ID extraction.
 *
 * @since 1.6.0
 */
@DreamDisplaysUnstableApi
interface MediaSearchService {
    /** Returns up to [limit] videos matching [query]. */
    fun search(query: String, limit: Int): List<MediaSearchResult>

    /** Returns up to [limit] videos related to the video identified by [videoId]. */
    fun related(videoId: String, limit: Int): List<MediaSearchResult>

    /** Returns the first page (up to [limit] videos) matching [query] in [sortOrder], plus a token for [searchMore]. */
    fun searchPage(query: String, limit: Int, sortOrder: SortOrder = SortOrder.RELEVANCE): MediaSearchPage

    /** Returns the next page (up to [limit] videos) following [continuationToken] from a prior [searchPage]/[searchMore] call. */
    fun searchMore(continuationToken: String, limit: Int): MediaSearchPage

    /** Returns the first page (up to [limit] videos) related to [videoId], plus a token for [relatedMore]. */
    fun relatedPage(videoId: String, limit: Int): MediaSearchPage

    /** Returns the next page (up to [limit] videos) following [continuationToken] from a prior [relatedPage]/[relatedMore] call. */
    fun relatedMore(continuationToken: String, limit: Int): MediaSearchPage

    /** Extracts a YouTube video ID from [url], or null if the URL is not a recognized YouTube link. */
    fun extractVideoId(url: String): String?

    /** Fetches rich metadata for [videoId] from the InnerTube API, or null on failure. */
    fun metadata(videoId: String): MediaSearchResult?
}
