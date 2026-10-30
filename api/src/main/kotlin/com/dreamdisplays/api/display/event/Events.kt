package com.dreamdisplays.api.display.event

import com.dreamdisplays.api.DisplayLifecycle
import com.dreamdisplays.api.Unstable
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
@Unstable
interface Events {
    /**
     * Calls [listener] for every event of [type] (or a subtype) in this scope.
     *
     * Close the returned handle to stop.
     */
    fun <T : DisplayEvent> on(type: Class<T>, listener: Consumer<T>): AutoCloseable

    /**
     * Describes how to react to the playback life of the displays in this scope in one block.
     *
     * Handlers get no display, so this fits `Display.events`; on a wider scope use [on] to know which
     * display fired.
     */
    fun lifecycle(configure: Consumer<DisplayLifecycle>): AutoCloseable
}
