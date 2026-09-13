package com.dreamdisplays.platform.client.input

/**
 * Service for handling interactions with displays, such as right-clicking or looking at them.
 * Provides raycast-based target resolution and click handling.
 */
interface DisplayInteractionService {
    /** Performs a raycast to determine which display is currently being looked at, if any. */
    fun raycast(): RaycastResult

    /** Returns the display that is currently being targeted by the player's cursor, if any. */
    fun getCurrentTarget(): RaycastResult.Hit?

    /** Emits an interaction event to all registered listeners. */
    fun emit(interaction: DisplayInteraction)

    /** Registers a listener for display interaction events; close the returned handle to unregister it. */
    fun on(listener: (DisplayInteraction) -> Unit): AutoCloseable
}
