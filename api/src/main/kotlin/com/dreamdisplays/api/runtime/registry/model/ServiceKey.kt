package com.dreamdisplays.api.runtime.registry.model

import com.dreamdisplays.api.Unstable

/**
 * Stable key for a service binding; [id] allows multiple bindings per contract type.
 *
 * @since 1.8.x
 */
@Unstable
data class ServiceKey<T : Any>(
    val id: String,
    val type: Class<T>,
) {
    init {
        require(id.isNotBlank()) { "Service key id must not be blank." }
    }

    override fun toString(): String = "$id:${type.name}"
}

/** Creates a [ServiceKey] for [T] without requiring Kotlin reflection. */
@Unstable
inline fun <reified T : Any> serviceKey(id: String): ServiceKey<T> = ServiceKey(id, T::class.java)

/** Default key used by class-based service lookups. */
@Unstable
fun <T : Any> serviceKey(type: Class<T>): ServiceKey<T> = ServiceKey(type.name, type)
