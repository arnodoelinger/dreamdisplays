package com.dreamdisplays.platform.client.core

import com.dreamdisplays.api.runtime.module.DreamDisplaysRuntime

/**
 * Represents the main application for the client.
 */
interface ClientApplication : DreamDisplaysRuntime {
    /** Context for the application, providing access to state, services, and platform APIs. */
    val context: ClientContext

    /** Emits a lifecycle event to all registered modules. */
    fun emit(event: ClientLifecycleEvent)

    /** Subscribes a listener to lifecycle events. */
    fun onEvent(listener: (ClientLifecycleEvent) -> Unit): AutoCloseable
}
