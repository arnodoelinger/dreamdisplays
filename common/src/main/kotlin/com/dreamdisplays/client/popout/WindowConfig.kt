package com.dreamdisplays.client.popout

/**
 * Configuration for a pop-out window.
 *
 * @since 1.7.0
 */
data class WindowConfig(
    /** Initial width of the window in pixels. Default is 640. */
    val initialWidth: Int = 640,

    /** Initial height of the window in pixels. Default is 360. */
    val initialHeight: Int = 360,

    /** The title of the window. */
    val title: String = "DreamDisplays", // TODO: default title?

    /** Whether the window should always be on top. Default is false. */
    val alwaysOnTop: Boolean = false,

    /** The backend to use for creating and rendering the window. Default is the detected default backend. */
    val backend: WindowBackend = WindowBackend.detectDefault(),

    /** Whether the window should be resizable. Default is true. */
    val resizable: Boolean = true,
)
