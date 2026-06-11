package com.dreamdisplays.media.api

/**
 * Typed exception hierarchy for media failures. Every subclass carries a machine-readable
 * [kind] and an [isFatal] flag; fatal errors cannot recover without user intervention.
 *
 * @property kind machine-readable failure category
 * @property isFatal true when the failure is unrecoverable without user intervention
 *
 * @since 1.8.0
 */
sealed class DreamMediaException(
    message: String,
    cause: Throwable? = null,
    val kind: MediaFailureKind,
    val isFatal: Boolean,
) : Exception(message, cause) {

    /** Network connectivity or HTTP-level failure. */
    class Network(message: String, cause: Throwable? = null, isFatal: Boolean = false) :
        DreamMediaException(message, cause, MediaFailureKind.NETWORK, isFatal)

    /** Decoder or codec failure while rendering a stream. */
    class Decode(message: String, cause: Throwable? = null, isFatal: Boolean = false) :
        DreamMediaException(message, cause, MediaFailureKind.DECODE, isFatal)

    /** No usable streams or media found for the requested source. */
    class NotFound(message: String, cause: Throwable? = null) :
        DreamMediaException(message, cause, MediaFailureKind.NOT_FOUND, isFatal = true)

    /** Content is region-locked and unavailable to this client. */
    class GeoBlocked(message: String, cause: Throwable? = null) :
        DreamMediaException(message, cause, MediaFailureKind.GEO_BLOCKED, isFatal = true)

    /** Operation timed out before completing. */
    class Timeout(message: String, cause: Throwable? = null, isFatal: Boolean = false) :
        DreamMediaException(message, cause, MediaFailureKind.TIMEOUT, isFatal)

    /** Failure that does not fit a more specific category. */
    class Unknown(message: String, cause: Throwable? = null, isFatal: Boolean = false) :
        DreamMediaException(message, cause, MediaFailureKind.UNKNOWN, isFatal)
}
