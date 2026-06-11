package com.dreamdisplays.client.input

/**
 * Handles input actions.
 *
 * @since 1.0.0
 */
interface InputHandler {
    /** @return true if the action was consumed and should not propagate */
    fun handle(action: InputAction): Boolean

    /** Handlers with higher priority are offered actions first. Default is 0. */
    val priority: Int get() = 0
}
