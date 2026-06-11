package com.dreamdisplays.client.input

import java.util.concurrent.ConcurrentHashMap

/**
 * Default [KeyBindingRegistry]: an id-keyed map of the mod's logical input bindings. Pollers
 * (e.g. the tick handler) look bindings up by id instead of hardcoding `GLFW` codes, so a binding
 * can be redefined in one place.
 *
 * @since 1.8.0
 */
class DefaultKeyBindingRegistry : KeyBindingRegistry {
    /** Map of registered bindings by id. [ConcurrentHashMap] allows concurrent access and modification. */
    private val bindings = ConcurrentHashMap<String, KeyBinding>()

    /** Registers [binding] under its id; re-registering an id returns the already-stored binding. */
    override fun register(binding: KeyBinding): KeyBinding = bindings.putIfAbsent(binding.id, binding) ?: binding

    /** Snapshot of all registered bindings. */
    override fun getAll(): List<KeyBinding> = bindings.values.toList()

    /** Returns the binding registered under [id], or null. */
    override fun findById(id: String): KeyBinding? = bindings[id]

    /** Removes every registered binding. */
    override fun reset() = bindings.clear()
}
