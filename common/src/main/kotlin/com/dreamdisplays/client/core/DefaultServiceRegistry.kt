package com.dreamdisplays.client.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe [ServiceRegistry] backed by a [ConcurrentHashMap] keyed on the contract [Class].
 *
 * @since 1.8.0
 */
class DefaultServiceRegistry : ServiceRegistry {
    /** Map of contract types to service instances. */
    private val instances = ConcurrentHashMap<Class<*>, Any>()

    /** Registers [instance] under contract [type], replacing any previous binding for that type. */
    override fun <T : Any> register(type: Class<T>, instance: T) { instances[type] = instance }

    /** Returns the instance bound to [type], throwing if nothing was registered. */
    override fun <T : Any> get(type: Class<T>): T = getOrNull(type) ?: error("No service registered for ${type.name}.")

    /** Returns the instance bound to [type], or null if none was registered. */
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getOrNull(type: Class<T>): T? = instances[type] as T?

    /** Returns true if an instance is bound to [type]. */
    override fun <T : Any> has(type: Class<T>): Boolean = instances.containsKey(type)
}
