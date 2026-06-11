package com.dreamdisplays.client.core

/**
 * Represents the main application for the client.
 *
 * @since 1.8.0
 */
interface ClientApplication {
    /** Context for the application, providing access to state, services, and platform APIs. */
    val context: ClientContext

    /** Registers a module with the application. Modules will be installed in the order they are registered. */
    fun registerModule(module: ClientModule)

    /** Starts the application. */
    fun start()

    /** Stops the application. */
    fun stop()

    /** Emits a lifecycle event to all registered modules. */
    fun emit(event: ClientLifecycleEvent)

    /** Subscribes a listener to lifecycle events. */
    fun onEvent(listener: (ClientLifecycleEvent) -> Unit): AutoCloseable
}
