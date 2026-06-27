package com.dreamdisplays.api.runtime

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Stable key for a service binding.
 *
 * [id] allows multiple bindings for the same contract type when needed, while the default
 * class-based registry helpers use the contract class name as the id.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
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
@DreamDisplaysUnstableApi
inline fun <reified T : Any> serviceKey(id: String): ServiceKey<T> = ServiceKey(id, T::class.java)

/** Default key used by class-based service lookups. */
@DreamDisplaysUnstableApi
fun <T : Any> serviceKey(type: Class<T>): ServiceKey<T> = ServiceKey(type.name, type)
