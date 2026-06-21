package com.dreamdisplays.platform.client.overlay

/**
 * Represents an event related to overlays, such as a request to close the overlay.
 */
sealed interface OverlayEvent {
    /** Represents a request to close the overlay. The [animated] parameter indicates whether the closing should be animated. */
    data class CloseRequested(val animated: Boolean = true) : OverlayEvent
}
