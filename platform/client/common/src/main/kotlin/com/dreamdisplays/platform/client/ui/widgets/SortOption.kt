package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.api.media.search.SortOrder
import net.minecraft.network.chat.Component

/**
 * Sort/filter choices offered by the suggestions panel's sort dropdown. [RELEVANCE ] /[POPULARITY] /
 * [NEWEST] / [STREAMS] map to a YouTube [SortOrder] and re-run the current search server-side;
 * [UNWATCHED] / [WATCHED] are purely local filters over whatever is already loaded (see
 * [WatchedVideoStore][com.dreamdisplays.platform.client.storage.WatchedVideoStore]), so they never
 * trigger a network call.
 */
enum class SortOption(val labelKey: String, val networkSort: SortOrder) {
    RELEVANCE("dreamdisplays.sort.relevance", SortOrder.RELEVANCE),
    POPULARITY("dreamdisplays.sort.popularity", SortOrder.VIEW_COUNT),
    NEWEST("dreamdisplays.sort.newest", SortOrder.UPLOAD_DATE),
    STREAMS("dreamdisplays.sort.streams", SortOrder.LIVE),
    UNWATCHED("dreamdisplays.sort.unwatched", SortOrder.RELEVANCE),
    WATCHED("dreamdisplays.sort.watched", SortOrder.RELEVANCE);

    /** True when picking this option should re-run the current search against `YouTube`'s own sort. */
    val refetches: Boolean get() = this == RELEVANCE || this == POPULARITY || this == NEWEST || this == STREAMS

    fun label(): String = Component.translatable(labelKey).string
}
