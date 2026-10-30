package com.dreamdisplays.api

import com.dreamdisplays.api.display.event.Events
import com.dreamdisplays.api.display.model.property.DisplayId

/**
 * Owner of subscriptions. Everything started through a session ends when the session is closed.
 *
 * Example:
 * ```
 * DreamDisplays.session("my-addon") {
 *     val screen = display(id) ?: return@session
 *     screen.lifecycle { onFailed { screen.retry() } }
 * }   // Closed here, every subscription above is gone
 * ```
 *
 * @since 1.10.x
 */
interface Session : AutoCloseable {
    /** Name shown in diagnostics. */
    val name: String

    /** True after [close]. */
    val isClosed: Boolean

    /** Like [DreamDisplays.display], but subscriptions made on the result belong to this session. */
    fun display(id: DisplayId): Display?

    /** Like [DreamDisplays.displays], but [Displays.each] subscriptions belong to this session. */
    val displays: Displays

    /** Like [DreamDisplays.events]; every subscription made through it ends with this session. */
    val events: Events

    /**
     * Makes [resource] close together with this session; closed in reverse order.
     *
     * @return [resource].
     */
    fun <T : AutoCloseable> own(resource: T): T

    override fun close()
}
