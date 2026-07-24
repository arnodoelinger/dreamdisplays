package com.dreamdisplays.api.media.search

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * `YouTube` search ordering, carrying the base64 `InnerTube` `params` value ("sp") that requests it.
 * Values reverse-engineered against `youtubei/v1/search` (see the [spParam] docs for the decoded
 * protobuf shape); [RELEVANCE] omits the field entirely, which is `YouTube`'s own default order.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
enum class SortOrder(val spParam: String?) {
    /** Default relevance ranking; no `params` field is sent. */
    RELEVANCE(null),

    /** Sort by upload date, newest first. Decodes to protobuf `{1: 2}`. */
    UPLOAD_DATE("CAI="),

    /** Sort by view count, highest first. Decodes to protobuf `{1: 3}`. */
    VIEW_COUNT("CAM="),

    /** Filter to currently-live streams only. Decodes to protobuf `{2: {8: 1}}`. */
    LIVE("EgJAAQ=="),
}
