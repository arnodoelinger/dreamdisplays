package com.dreamdisplays.client.popout

import com.dreamdisplays.media.api.VideoFrameSink

/**
 * Represents an open popout window. Provides properties for the window's status and dimensions, as well as methods to
 * subscribe to events and close the window.
 *
 * @since 1.7.0
 */
interface PopoutWindow : AutoCloseable {
    /** True if the window is currently open. */
    val isOpen: Boolean

    /** The width of the window in pixels. May be zero if the window is not open or if the backend does not provide this information. */
    val width: Int

    /** The height of the window in pixels. May be zero if the window is not open or if the backend does not provide this information. */
    val height: Int

    /** The backend used to create and render this window. Useful for debugging and conditional logic based on backend capabilities. */
    val backend: WindowBackend

    /**
     * Opens the window with the specified configuration and returns a [VideoFrameSink] for rendering video frames to it.
     * May throw an exception if the window fails to open.
     */
    fun open(config: WindowConfig): VideoFrameSink

    /** Closes the window. If the window is already closed, does nothing. May throw an exception if the window fails to close properly. */
    override fun close()

    /**
     * Subscribes to events related to this window, such as resize, focus changes, and backend failures.
     * The [listener] will be called with a [PopoutEvent] whenever an event occurs. Returns an [AutoCloseable] that can be
     * used to unsubscribe from events.
     */
    fun on(listener: (PopoutEvent) -> Unit): AutoCloseable
}
