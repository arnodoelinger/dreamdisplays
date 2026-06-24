package com.dreamdisplays.platform.client.input

/**
 * Handles input actions.
 */
interface InputHandler {
    /** @return true if the action was consumed and should not propagate */
    fun handle(action: InputAction): Boolean

    /** Handlers with higher priority are offered actions first. Default is 0. */
    val priority: Int get() = 0
}
