package com.dreamdisplays.api.media.search.model

import com.dreamdisplays.api.Unstable

/**
 * One page of search or related-video results, plus the token needed to fetch the next page.
 *
 * @since 1.9.x
 */
@Unstable
data class MediaSearchPage(
    /** Results on this page. */
    val results: List<MediaSearchResult>,

    /** Opaque token to pass to a follow-up "more" call for the next page, or null when exhausted. */
    val continuationToken: String? = null,
)
