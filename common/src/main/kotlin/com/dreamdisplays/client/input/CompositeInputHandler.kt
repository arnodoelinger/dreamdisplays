package com.dreamdisplays.client.input

import java.util.concurrent.CopyOnWriteArrayList

/**
 * [InputHandler] chain: dispatches each [InputAction] to registered handlers in descending
 * [InputHandler.priority] order until one consumes it. Registered in the service registry as the
 * single [InputHandler] entry point for raw input.
 *
 * @since 1.8.0
 */
class CompositeInputHandler : InputHandler {
    /** Handlers registered to this chain. [CopyOnWriteArrayList] allows concurrent modification while iterating. */
    private val handlers = CopyOnWriteArrayList<InputHandler>()

    /** Adds [handler] to the chain; close the returned handle to remove it. */
    fun register(handler: InputHandler): AutoCloseable {
        handlers += handler
        return AutoCloseable { handlers -= handler }
    }

    /** Offers [action] to each handler by priority; true once the first handler consumes it. */
    override fun handle(action: InputAction): Boolean =
        handlers.sortedByDescending { it.priority }.any { it.handle(action) }
}
