package com.dreamdisplays.client.input

/**
 * Represents an input action.
 *
 * @since 1.0.0
 */
sealed interface InputAction {
    data class MouseClicked(val x: Double, val y: Double, val button: Int) : InputAction
}
