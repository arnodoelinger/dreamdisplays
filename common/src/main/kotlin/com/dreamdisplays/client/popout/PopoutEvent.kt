package com.dreamdisplays.client.popout

/**
 * Lifecycle events for popout surfaces (detached windows and PiP overlays). Emitted through
 * [PopoutManager.emit] and observed via [PopoutManager.on]; every event names the display it
 * belongs to so global subscribers can tell popouts apart.
 *
 * @since 1.7.0
 */
sealed interface PopoutEvent {
    /** The display whose popout produced this event. */
    val displayId: String

    /** A popout surface (window or PiP) became visible. */
    data class Opened(override val displayId: String) : PopoutEvent

    /** A popout surface was closed, by the user or programmatically. */
    data class Closed(override val displayId: String) : PopoutEvent

//    /** The popout window was resized to [width]x[height] pixels. */
//    data class Resized(override val displayId: String, val width: Int, val height: Int) : PopoutEvent
//
//    /** The popout window gained OS focus. */
//    data class FocusGained(override val displayId: String) : PopoutEvent
//
//    /** The popout window lost OS focus. */
//    data class FocusLost(override val displayId: String) : PopoutEvent

    /** The windowing backend failed to open or render; [reason] is best-effort diagnostics. */
    data class BackendFailed(override val displayId: String, val reason: String? = null) : PopoutEvent
}
