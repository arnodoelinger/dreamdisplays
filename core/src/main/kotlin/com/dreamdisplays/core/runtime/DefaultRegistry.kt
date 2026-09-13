package com.dreamdisplays.core.runtime

import com.dreamdisplays.api.runtime.module.ModuleContext
import com.dreamdisplays.api.runtime.registry.model.ServiceKey
import com.dreamdisplays.api.runtime.registry.service.ServiceRegistry
import java.util.concurrent.ConcurrentHashMap

/** Service. */
class DefaultServiceRegistry : ServiceRegistry {
    private val instances = ConcurrentHashMap<ServiceKey<*>, Any>()

    override fun <T : Any> register(key: ServiceKey<T>, instance: T) {
        require(key.type.isInstance(instance)) {
            "Service instance for $key must implement ${key.type.name}."
        }
        instances[key] = instance
    }

    override fun <T : Any> getOrNull(key: ServiceKey<T>): T? =
        instances[key]?.let(key.type::cast)
}

class DefaultModuleContext(
    override val services: ServiceRegistry,
) : ModuleContext
