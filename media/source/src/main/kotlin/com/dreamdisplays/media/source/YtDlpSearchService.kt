package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.search.model.MediaSearchPage
import com.dreamdisplays.api.media.search.model.MediaSearchResult
import com.dreamdisplays.api.media.search.service.MediaSearchService
import com.dreamdisplays.api.media.search.model.SortOrder
import com.dreamdisplays.media.source.youtube.YouTubeInnerTube
import com.dreamdisplays.media.source.youtube.YtDlp

/** [MediaSearchService] backed by [YtDlp] and [YouTubeInnerTube]. */
class YtDlpSearchService : MediaSearchService {
    override fun search(query: String, limit: Int): List<MediaSearchResult> = YtDlp.search(query, limit)
    override fun related(videoId: String, limit: Int): List<MediaSearchResult> = YtDlp.related(videoId, limit)
    override fun searchPage(query: String, limit: Int, sortOrder: SortOrder): MediaSearchPage = YtDlp.searchPage(query, limit, sortOrder)
    override fun searchMore(continuationToken: String, limit: Int): MediaSearchPage = YtDlp.searchMore(continuationToken, limit)
    override fun relatedPage(videoId: String, limit: Int): MediaSearchPage = YtDlp.relatedPage(videoId, limit)
    override fun relatedMore(continuationToken: String, limit: Int): MediaSearchPage = YtDlp.relatedMore(continuationToken, limit)
    override fun extractVideoId(url: String): String? = YtDlp.extractVideoId(url)
    override fun metadata(videoId: String): MediaSearchResult? = YouTubeInnerTube.metadata(videoId)
}
