package com.dreamdisplays.core.runtime

import com.dreamdisplays.api.runtime.ServiceKey
import com.dreamdisplays.api.runtime.ServiceRegistry
import com.dreamdisplays.api.runtime.serviceKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe [ServiceRegistry] backed by a [ConcurrentHashMap].
 */
class DefaultServiceRegistry : ServiceRegistry {
    private val instances = ConcurrentHashMap<ServiceKey<*>, Any>()

    override fun <T : Any> register(key: ServiceKey<T>, instance: T) {
        require(key.type.isInstance(instance)) {
            "Service instance for $key must implement ${key.type.name}."
        }
        instances[key] = instance
        instances[serviceKey(key.type)] = instance
    }

    override fun <T : Any> getOrNull(key: ServiceKey<T>): T? =
        instances[key]?.let(key.type::cast)
}
