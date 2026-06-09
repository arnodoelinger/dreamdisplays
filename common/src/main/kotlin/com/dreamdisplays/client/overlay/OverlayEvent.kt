package com.dreamdisplays.client.overlay

/**
 * Represents an event related to overlays, such as a request to close the overlay.
 *
 * @since 1.7.0
 */
sealed interface OverlayEvent {
    /** Represents a request to close the overlay. The [animated] parameter indicates whether the closing should be animated. */
    data class CloseRequested(val animated: Boolean = true) : OverlayEvent
}
