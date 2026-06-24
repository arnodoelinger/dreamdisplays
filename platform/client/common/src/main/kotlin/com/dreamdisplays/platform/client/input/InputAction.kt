package com.dreamdisplays.platform.client.input

/**
 * Represents an input action.
 */
sealed interface InputAction {
    /** A mouse click at screen coordinates ([x], [y]) with the given mouse [button]. */
    data class MouseClicked(val x: Double, val y: Double, val button: Int) : InputAction
}
