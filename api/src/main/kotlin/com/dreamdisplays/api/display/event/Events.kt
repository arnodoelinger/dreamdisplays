package com.dreamdisplays.api.display.event

import com.dreamdisplays.api.DisplayLifecycle
import java.util.function.Consumer

/**
 * Listen to display events.
 *
 * `DreamDisplays.events` hears every display, `Session.events` the same but ends with the session, and
 * `Display.events` only that display and ends when it is removed.
 *
 * Example:
 * ```
 * DreamDisplays.events.on<DisplayEvent.UrlChanged> { println("${it.displayId} -> ${it.url}") }
 * display.events.on<DisplayEvent.MediaError> { display.retry() }
 * ```
 *
 * @since 1.10.x
 */
interface Events {
    /**
     * Calls [listener] for every event of [type] (or a subtype) in this scope.
     *
     * Close the returned handle to stop.
     */
    fun <T : DisplayEvent> on(type: Class<T>, listener: Consumer<T>): AutoCloseable
}

/**
 * [Events] of a single display, which also knows how to describe its playback life in one block.
 *
 * @since 1.10.x
 */
interface DisplayEvents : Events {
    /**
     * Describes how to react to this display's playback life in one block;
     * ends by itself when the display is removed.
     *
     * `Kotlin` code can use the nicer `display.lifecycle { onPlaying { ... } }` extension.
     */
    fun lifecycle(configure: Consumer<DisplayLifecycle>): AutoCloseable
}
