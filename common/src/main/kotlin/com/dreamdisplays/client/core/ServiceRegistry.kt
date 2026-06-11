package com.dreamdisplays.client.core

/**
 * Registry for services available to the client. Modules can use this to provide and consume services, allowing for
 * loose coupling and modularity. Services are identified by their type, and can be registered and retrieved at runtime.
 *
 * @since 1.8.0
 * */
interface ServiceRegistry {
    /** Registers [instance] under contract [type], replacing any previous binding for that type. */
    fun <T : Any> register(type: Class<T>, instance: T)

    /** Returns the instance bound to [type], throwing if nothing was registered. */
    fun <T : Any> get(type: Class<T>): T

    /** Returns the instance bound to [type], or null if none was registered. */
    fun <T : Any> getOrNull(type: Class<T>): T?

    /** Returns true if an instance is bound to [type]. */
    fun <T : Any> has(type: Class<T>): Boolean
}

inline fun <reified T : Any> ServiceRegistry.register(instance: T): Unit = register(T::class.java, instance)
inline fun <reified T : Any> ServiceRegistry.get(): T = get(T::class.java)
inline fun <reified T : Any> ServiceRegistry.getOrNull(): T? = getOrNull(T::class.java)
inline fun <reified T : Any> ServiceRegistry.has(): Boolean = has(T::class.java)
