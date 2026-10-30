package com.dreamdisplays.api.server

/**
 * Things that happen to displays on the server.
 *
 * @since 1.10.x
 */
sealed interface ServerDisplayEvent {
    /** The display the event is about. */
    val display: ServerDisplay

    /** A display was created. */
    data class Created(override val display: ServerDisplay) : ServerDisplayEvent

    /** A display was removed. */
    data class Removed(override val display: ServerDisplay) : ServerDisplayEvent

    /** A display was moved to another place with [ServerDisplay.moveTo]. */
    data class Moved(override val display: ServerDisplay) : ServerDisplayEvent

    /** Addon data on a display changed; [keys] are the keys that were added, changed or removed. */
    data class DataChanged(override val display: ServerDisplay, val keys: Set<String>) : ServerDisplayEvent

    /** A display's video link changed to [url]. */
    data class UrlChanged(override val display: ServerDisplay, val url: String) : ServerDisplayEvent
}
