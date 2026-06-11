package com.dreamdisplays.client.popout

import com.dreamdisplays.api.DisplayId
import com.dreamdisplays.media.api.VideoFrameSink

/**
 * Manages popout windows and picture-in-picture (PiP) for displays. Allows opening and closing popout windows and PiP,
 * checking their status, and subscribing to events related to them.
 *
 * @since 1.7.0
 */
interface PopoutManager {
    /** Opens a new popout window for the specified display. Returns the created sink, or null if a window for that display already exists. */
    fun openWindow(displayId: DisplayId, config: WindowConfig = WindowConfig()): VideoFrameSink?

    /** Opens the in-game PiP overlay for the specified display. Returns the created sink, or null if a PiP for that display already exists. */
    fun openPip(displayId: DisplayId): VideoFrameSink?

    /** Closes whichever popout mode is active for the specified display. If no popout is active, does nothing. */
    fun close(displayId: DisplayId)

    /** Closes all popouts on all loaded displays. */
    fun closeAll()

    /** True if the specified display has a detached window open. Inferred as "popout is active but no PiP overlay is registered with the [OverlayManager]". */
    fun isWindowOpen(displayId: DisplayId): Boolean

    /** True if the specified display has an active PiP overlay registered with the [OverlayManager]. */
    fun isPipOpen(displayId: DisplayId): Boolean

    /** Returns the video sink for the active popout mode of the specified display, or null if no popout is active. */
    fun getPopoutSink(displayId: DisplayId): VideoFrameSink?

    /**
     * Subscribes to popout lifecycle events. The [listener] will be called whenever a popout is opened, closed, or
     * encounters a backend failure; the event's [PopoutEvent.displayId] identifies which display the event belongs to.
     *
     * Returns an [AutoCloseable] that can be used to unsubscribe. */
    fun on(listener: (PopoutEvent) -> Unit): AutoCloseable

    /** Publishes [event] to all [on] subscribers. Called by the popout surfaces themselves. */
    fun emit(event: PopoutEvent)
}
