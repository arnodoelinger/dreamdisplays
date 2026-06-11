package com.dreamdisplays.client.input

/**
 * Represents a key binding.
 *
 * @since 1.0.0
 */
data class KeyBinding(
    /** The unique identifier for this key binding, used for registration and lookup. */
    val id: String,

    /** The default key code for this key binding. */
    val defaultKey: Int,

    /** The category of this key binding, used for grouping in the controls menu. */
    val category: String,

    /** A human-readable description of this key binding, shown in the controls menu. */
    val description: String,
)
