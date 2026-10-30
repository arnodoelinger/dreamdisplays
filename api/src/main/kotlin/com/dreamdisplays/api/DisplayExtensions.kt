package com.dreamdisplays.api

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.event.Events
import java.util.function.Consumer

/**
 * Describes how to react to the display's life in one block. Ends by itself when the display is removed.
 *
 * Example:
 * ```
 * display.lifecycle {
 *     onPlaying { println("Playing!") }
 *     onFailed  { display.retry() }
 *     onRemoved { println("Gone!") }
 * }
 * ```
 *
 * @since 1.10.x
 */
inline fun Display.lifecycle(crossinline block: DisplayLifecycle.() -> Unit): AutoCloseable =
    events.lifecycle { it.block() }

/**
 * Listens to events of type [T] in this scope.
 *
 * Example:
 * ```
 * display.events.on<DisplayEvent.StateChanged> { println(it.current) }
 * ```
 *
 * @since 1.10.x
 */
inline fun <reified T : DisplayEvent> Events.on(crossinline action: (T) -> Unit): AutoCloseable =
    on(T::class.java, Consumer { action(it) })
