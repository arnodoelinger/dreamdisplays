package com.dreamdisplays.client.core

/**
 * Client module: a self-contained unit of functionality, which can be installed into the client context.
 *
 * @since 1.8.0
 */
interface ClientModule {
    /** Unique identifier for this module. Should be in the format `namespace:name`. */
    val id: String

    /**
     * List of module IDs that this module depends on. Modules will be installed in dependency order, and
     * if any dependencies are missing, the module will not be installed.
     */
    val dependencies: List<String> get() = emptyList()

    /** Installs this module into the given [context]. This will only be called once, and only after all dependencies have been installed. */
    fun install(context: ClientContext)

    /** Called when the client lifecycle changes. */
    fun onEvent(event: ClientLifecycleEvent) {}
}
