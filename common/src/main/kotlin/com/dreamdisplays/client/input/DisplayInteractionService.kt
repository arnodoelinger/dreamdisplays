package com.dreamdisplays.client.input

/**
 * Service for handling interactions with displays, such as right-clicking or looking at them. This service provides
 * methods for raycasting to determine which display is being interacted with, emitting interaction events, and
 * registering listeners for those events. It is used by input handlers to process user interactions with displays and
 * by modules to respond to those interactions.
 *
 * @since 1.8.0
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
