package com.dreamdisplays.platform.client.input

/**
 * Represents an input action.
 */
sealed interface InputAction {
    data class MouseClicked(val x: Double, val y: Double, val button: Int) : InputAction
}
