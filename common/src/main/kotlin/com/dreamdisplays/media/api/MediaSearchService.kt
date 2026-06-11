package com.dreamdisplays.media.api

/** Contract for YouTube search, related-video lookup, and video-ID extraction. */
interface MediaSearchService {
    /** Returns up to [limit] videos matching [query]. */
    fun search(query: String, limit: Int): List<MediaSearchResult>

    /** Returns up to [limit] videos related to the video identified by [videoId]. */
    fun related(videoId: String, limit: Int): List<MediaSearchResult>

    /** Extracts a YouTube video ID from [url], or null if the URL is not a recognized YouTube link. */
    fun extractVideoId(url: String): String?

    /** Fetches rich metadata for [videoId] from the InnerTube API, or null on failure. */
    fun metadata(videoId: String): MediaSearchResult?
}
