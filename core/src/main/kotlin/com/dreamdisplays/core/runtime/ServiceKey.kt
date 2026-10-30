package com.dreamdisplays.core.runtime

/** Stable key for a service binding; [id] allows multiple bindings per contract type. */
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
inline fun <reified T : Any> serviceKey(id: String): ServiceKey<T> = ServiceKey(id, T::class.java)

/** Default key used by class-based service lookups. */
fun <T : Any> serviceKey(type: Class<T>): ServiceKey<T> = ServiceKey(type.name, type)
