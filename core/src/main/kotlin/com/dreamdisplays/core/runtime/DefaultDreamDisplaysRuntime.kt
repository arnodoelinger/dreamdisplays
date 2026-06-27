package com.dreamdisplays.core.runtime

import com.dreamdisplays.api.runtime.DreamDisplaysModule
import com.dreamdisplays.api.runtime.DreamDisplaysRuntime
import com.dreamdisplays.api.runtime.ModuleContext
import com.dreamdisplays.api.runtime.ServiceRegistry

/**
 * Default module host for Dream Displays.
 *
 * The host owns only construction concerns: module registration, dependency ordering, and
 * teardown. Feature implementations still live in their dedicated `core`, `media`, or platform
 * modules and are exposed through API contracts.
 */
class DefaultDreamDisplaysRuntime(
    private val context: ModuleContext = DefaultModuleContext(DefaultServiceRegistry()),
) : DreamDisplaysRuntime {
    /** Live module instances. */
    private val modules = LinkedHashMap<String, DreamDisplaysModule>()

    /** Installed module ids in install order. */
    private val installed = LinkedHashSet<String>()

    /** Whether the runtime has been started. */
    @Volatile
    private var started = false

    /** Creates a new runtime with the given [services]. */
    constructor(services: ServiceRegistry) : this(DefaultModuleContext(services))

    /** Service registry for the runtime. */
    override val services: ServiceRegistry; get() = context.services

    /** Registered module IDs. */
    override val registeredModuleIds: Set<String>; get() = modules.keys.toSet()

    /** Installed module IDs. */
    override val installedModuleIds: Set<String>; get() = installed.toSet()

    /** Registers [module]. If the runtime is already started, the module is installed immediately. */
    @Synchronized
    override fun registerModule(module: DreamDisplaysModule) {
        require(module.id.isNotBlank()) { "Module id must not be blank." }
        require(module.id !in modules) { "Module '${module.id}' is already registered." }
        modules[module.id] = module
        if (started) install(module)
    }

    /** Installs all registered modules in dependency order. */
    @Synchronized
    override fun start() {
        if (started) return
        runCatching { modules.values.forEach(::install) }
            .onFailure {
                uninstallInstalled()
                throw it
            }
        started = true
    }

    /** Uninstalls all installed modules in reverse dependency order. */
    @Synchronized
    override fun stop() {
        if (!started) return
        uninstallInstalled()
        started = false
    }

    /** Installs [module] and all its dependencies. */
    private fun install(module: DreamDisplaysModule, chain: List<String> = emptyList()) {
        if (module.id in installed) return
        check(module.id !in chain) {
            "Circular module dependency: ${(chain + module.id).joinToString(" -> ")}."
        }
        for (dependencyId in module.dependencies) {
            val dependency = checkNotNull(modules[dependencyId]) {
                "Module '${module.id}' depends on unregistered module '$dependencyId'."
            }
            install(dependency, chain + module.id)
        }
        module.install(context)
        installed += module.id
    }

    /** Uninstalls all installed modules. */
    private fun uninstallInstalled() {
        installed.toList().asReversed().forEach { moduleId ->
            modules[moduleId]?.uninstall(context)
        }
        installed.clear()
    }
}
