package com.dreamdisplays.platform.client.core

import com.dreamdisplays.api.runtime.module.DreamDisplaysModule
import com.dreamdisplays.api.runtime.registry.service.ServiceRegistry
import com.dreamdisplays.core.runtime.DefaultRuntime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default [ClientApplication]: a module host over a [ClientContext]. Modules are installed in dependency order and
 * torn down in reverse on stop.
 */
class DefaultClientApplication(override val context: ClientContext) : ClientApplication {
    private val runtime = DefaultRuntime(context)
    private val clientModules = LinkedHashMap<String, ClientModule>()

    /** Listeners for lifecycle events. */
    private val listeners = CopyOnWriteArrayList<(ClientLifecycleEvent) -> Unit>()

    /** Whether the application has been started. */
    @Volatile
    private var started = false

    /** Service registry for the application. */
    override val services: ServiceRegistry; get() = runtime.services

    /** Registered module IDs. */
    override val registeredModuleIds: Set<String>; get() = runtime.registeredModuleIds

    /** Installed module IDs. */
    override val installedModuleIds: Set<String>; get() = runtime.installedModuleIds

    /**
     * Adds [module] to the application. Before [start] the install is deferred; after [start] the
     * module is installed immediately (its dependencies must already be installed).
     */
    @Synchronized
    override fun registerModule(module: DreamDisplaysModule) {
        if (module is ClientModule) {
            require(module.id !in clientModules) { "Client module '${module.id}' is already registered." }
            clientModules[module.id] = module
        }
        runtime.registerModule(module)
    }

    /** Emits [ClientLifecycleEvent.Initializing], installs all modules in dependency order, then [ClientLifecycleEvent.Ready]. */
    @Synchronized
    override fun start() {
        if (started) return
        emit(ClientLifecycleEvent.Initializing)
        runCatching { runtime.start() }
            .onFailure {
                runtime.stop()
                throw it
            }
        started = true
        emit(ClientLifecycleEvent.Ready)
    }

    /** Emits [ClientLifecycleEvent.ShuttingDown]; modules stay registered for a possible restart. */
    @Synchronized
    override fun stop() {
        if (!started) return
        emit(ClientLifecycleEvent.ShuttingDown)
        runtime.stop()
        started = false
    }

    /** Fans [event] out to external listeners first, then to every installed module. */
    override fun emit(event: ClientLifecycleEvent) {
        listeners.forEach { it(event) }
        installedModuleIds.forEach { clientModules[it]?.onEvent(event) }
    }

    /** Subscribes [listener] to lifecycle events; close the returned handle to unsubscribe. */
    override fun onEvent(listener: (ClientLifecycleEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}
