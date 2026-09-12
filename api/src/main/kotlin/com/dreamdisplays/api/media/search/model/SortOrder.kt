package com.dreamdisplays.api.media.search.model

import com.dreamdisplays.api.Unstable

/**
 * YouTube search ordering via base64 InnerTube `params` ("sp" field); RELEVANCE omits it.
 *
 * @since 1.9.x
 */
@Unstable
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
