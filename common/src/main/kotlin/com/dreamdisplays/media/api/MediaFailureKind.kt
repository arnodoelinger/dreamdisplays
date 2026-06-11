package com.dreamdisplays.media.api

/**
 * Classifies the reason a media operation failed.
 *
 * @since 1.8.0
 */
enum class MediaFailureKind {
    /** Remote resource could not be reached (DNS failure, TCP reset, HTTP error). */
    NETWORK,

    /** Decoder or codec failure after a stream was acquired. */
    DECODE,

    /** No streams or media found for the given source. */
    NOT_FOUND,

    /** Content is unavailable in the user's region. */
    GEO_BLOCKED,

    /** Operation exceeded its time budget before completing. */
    TIMEOUT,

    /** Catch-all for failures that do not fit a more specific kind. */
    UNKNOWN,
}
