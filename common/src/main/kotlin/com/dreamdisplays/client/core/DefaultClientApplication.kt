package com.dreamdisplays.client.core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default [ClientApplication]: a module host over a [ClientContext]. Modules are installed in
 * dependency order on [start]; lifecycle events fan out to external listeners (via [onEvent]) and
 * to every installed module's [ClientModule.onEvent].
 *
 * The application itself does not render videos, draw overlays, manage popouts, talk to YouTube, or
 * handle input. Those responsibilities belong to modules.
 *
 * Its job is boring (and it's great):
 *  - Install modules
 *  - Resolve dependencies
 *  - Dispatch lifecycle events
 *  - Shut everything down cleanly
 *
 * Modules declare dependencies through [ClientModule.dependencies]. During
 * [start], dependencies are installed before dependents. Missing dependencies
 * and dependency cycles are fatal because silently continuing would be even
 * dumber.
 *
 * Lifecycle events are dispatched through a single path instead of every
 * subsystem inventing its own special snowflake startup hook.
 *
 * Put everything in one place and don't make garbage.
 *
 * @since 1.8.0
*/
class DefaultClientApplication(override val context: ClientContext) : ClientApplication {
    private val modules = LinkedHashMap<String, ClientModule>()
    private val installed = LinkedHashSet<String>()
    private val listeners = CopyOnWriteArrayList<(ClientLifecycleEvent) -> Unit>()

    @Volatile private var started = false

    /**
     * Adds [module] to the application. Before [start] the install is deferred; after [start] the
     * module is installed immediately (its dependencies must already be installed).
     */
    @Synchronized override fun registerModule(module: ClientModule) {
        require(module.id !in modules) { "Module '${module.id}' is already registered." }
        modules[module.id] = module
        if (started) install(module)
    }

    /** Emits [ClientLifecycleEvent.Initializing], installs all modules in dependency order, then [ClientLifecycleEvent.Ready]. */
    @Synchronized override fun start() {
        if (started) return
        started = true
        emit(ClientLifecycleEvent.Initializing)
        modules.values.forEach(::install)
        emit(ClientLifecycleEvent.Ready)
    }

    /** Emits [ClientLifecycleEvent.ShuttingDown]; modules stay registered for a possible restart. */
    @Synchronized override fun stop() {
        if (!started) return
        emit(ClientLifecycleEvent.ShuttingDown)
        started = false
        installed.clear()
    }

    /** Fans [event] out to external listeners first, then to every installed module. */
    override fun emit(event: ClientLifecycleEvent) {
        listeners.forEach { it(event) }
        installed.forEach { modules[it]?.onEvent(event) }
    }

    /** Subscribes [listener] to lifecycle events; close the returned handle to unsubscribe. */
    override fun onEvent(listener: (ClientLifecycleEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Installs [module] after its dependencies, failing fast on unknown or circular dependencies. */
    private fun install(module: ClientModule, chain: List<String> = emptyList()) {
        if (module.id in installed) return
        check(module.id !in chain) {
            "Circular module dependency: ${(chain + module.id).joinToString(" -> ")}."
        }
        for (depId in module.dependencies) {
            val dep = checkNotNull(modules[depId]) {
                "Module '${module.id}' depends on unregistered module '$depId'."
            }
            install(dep, chain + module.id)
        }
        module.install(context)
        installed += module.id
    }
}
