package com.dreamdisplays.core.runtime

/** Registry of services available to the runtime; access by [ServiceKey] or by class. */
interface ServiceRegistry {
    /** Registers [instance] under [key], replacing any previous binding for that key. */
    fun <T : Any> register(key: ServiceKey<T>, instance: T)

    /** Registers [instance] under the default key for [type]. */
    fun <T : Any> register(type: Class<T>, instance: T): Unit = register(serviceKey(type), instance)

    /** @return the instance bound to [key], throwing if nothing was registered. */
    fun <T : Any> get(key: ServiceKey<T>): T = getOrNull(key) ?: error("No service registered for $key.")

    /** @return the instance bound to [key], or null if none was registered. */
    fun <T : Any> getOrNull(key: ServiceKey<T>): T?

    /** @return true if an instance is bound to [key]. */
    fun <T : Any> has(key: ServiceKey<T>): Boolean = getOrNull(key) != null

    /** @return the instance bound to the default key for [type], throwing if absent. */
    fun <T : Any> get(type: Class<T>): T = get(serviceKey(type))

    /** @return the instance bound to the default key for [type], or null if absent. */
    fun <T : Any> getOrNull(type: Class<T>): T? = getOrNull(serviceKey(type))

    /** @return true if an instance is bound to the default key for [type]. */
    fun <T : Any> has(type: Class<T>): Boolean = has(serviceKey(type))
}

/** Registers [instance] under the default key for [T]. */
inline fun <reified T : Any> ServiceRegistry.register(instance: T): Unit = register(T::class.java, instance)

/** @return the instance bound to the default key for [T], throwing if absent. */
inline fun <reified T : Any> ServiceRegistry.get(): T = get(T::class.java)

/** @return the instance bound to the default key for [T], or null if absent. */
inline fun <reified T : Any> ServiceRegistry.getOrNull(): T? = getOrNull(T::class.java)

/** @return true if an instance is bound to the default key for [T]. */
inline fun <reified T : Any> ServiceRegistry.has(): Boolean = has(T::class.java)
