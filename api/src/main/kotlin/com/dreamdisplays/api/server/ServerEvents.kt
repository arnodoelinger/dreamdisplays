package com.dreamdisplays.api.server

import java.util.function.Consumer

/**
 * Listen to display events on the server.
 *
 * Example:
 * ```
 * DreamDisplaysServer.events.on<ServerDisplayEvent.UrlChanged> { println("${it.display.id} -> ${it.url}") }
 * ```
 *
 * @since 1.10.x
 */
interface ServerEvents {
    /**
     * Calls [listener] for every event of [type] (or a subtype).
     *
     * Close the returned handle to stop.
     */
    fun <T : ServerDisplayEvent> on(type: Class<T>, listener: Consumer<T>): AutoCloseable
}

/**
 * Listens to events of type [T].
 *
 * @since 1.10.x
 */
inline fun <reified T : ServerDisplayEvent> ServerEvents.on(crossinline action: (T) -> Unit): AutoCloseable =
    on(T::class.java, Consumer { action(it) })
