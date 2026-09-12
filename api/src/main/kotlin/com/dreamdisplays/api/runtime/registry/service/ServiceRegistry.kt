package com.dreamdisplays.api.runtime.registry.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.runtime.registry.model.ServiceKey
import com.dreamdisplays.api.runtime.registry.model.serviceKey

/**
 * Registry of services available to the runtime; access by [ServiceKey] or by class.
 *
 * @since 1.8.x
 */
@Unstable
interface ServiceRegistry {
    /** Registers [instance] under [key], replacing any previous binding for that key. */
    fun <T : Any> register(key: ServiceKey<T>, instance: T)

    /** Returns the instance bound to [key], throwing if nothing was registered. */
    fun <T : Any> get(key: ServiceKey<T>): T = getOrNull(key) ?: error("No service registered for $key.")

    /** Returns the instance bound to [key], or null if none was registered. */
    fun <T : Any> getOrNull(key: ServiceKey<T>): T?

    /** Returns true if an instance is bound to [key]. */
    fun <T : Any> has(key: ServiceKey<T>): Boolean = getOrNull(key) != null

    /** Registers [instance] under the default key for [type]. */
    fun <T : Any> register(type: Class<T>, instance: T): Unit = register(serviceKey(type), instance)

    /** Returns the instance bound to the default key for [type], throwing if absent. */
    fun <T : Any> get(type: Class<T>): T = get(serviceKey(type))

    /** Returns the instance bound to the default key for [type], or null if absent. */
    fun <T : Any> getOrNull(type: Class<T>): T? = getOrNull(serviceKey(type))

    /** Returns true if an instance is bound to the default key for [type]. */
    fun <T : Any> has(type: Class<T>): Boolean = has(serviceKey(type))
}

/** Registers [instance] under the default key for [T]. */
@Unstable
inline fun <reified T : Any> ServiceRegistry.register(instance: T): Unit = register(T::class.java, instance)

/** Returns the instance bound to the default key for [T], throwing if absent. */
@Unstable
inline fun <reified T : Any> ServiceRegistry.get(): T = get(T::class.java)

/** Returns the instance bound to the default key for [T], or null if absent. */
@Unstable
inline fun <reified T : Any> ServiceRegistry.getOrNull(): T? = getOrNull(T::class.java)

/** Returns true if an instance is bound to the default key for [T]. */
@Unstable
inline fun <reified T : Any> ServiceRegistry.has(): Boolean = has(T::class.java)
