package com.dreamdisplays.client.input

/**
 * Registry for key bindings.
 *
 * @since 1.0.0
 */
interface KeyBindingRegistry {
    /** Registers a key binding. The returned instance may be the same as the input or a different one, depending on the implementation. */
    fun register(binding: KeyBinding): KeyBinding

    /** Returns a snapshot of all registered key bindings. Modifying the returned list does not affect the registry. */
    fun getAll(): List<KeyBinding>

    /** Returns the binding registered under [id], or null. */
    fun findById(id: String): KeyBinding?

    /** Removes every registered binding. */
    fun reset()
}
