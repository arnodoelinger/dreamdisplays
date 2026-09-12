package com.dreamdisplays.core.runtime

import com.dreamdisplays.api.runtime.module.DreamDisplaysModule
import com.dreamdisplays.api.runtime.module.DreamDisplaysRuntime
import com.dreamdisplays.api.runtime.module.ModuleContext
import com.dreamdisplays.api.runtime.registry.service.ServiceRegistry

/** Default module host. */
class DefaultRuntime(
    private val context: ModuleContext = DefaultModuleContext(DefaultServiceRegistry()),
) : DreamDisplaysRuntime {
    private val modules = LinkedHashMap<String, DreamDisplaysModule>()
    private val installed = LinkedHashSet<String>()

    @Volatile
    private var started = false

    override val services: ServiceRegistry; get() = context.services
    override val registeredModuleIds: Set<String>; get() = modules.keys.toSet()
    override val installedModuleIds: Set<String>; get() = installed.toSet()

    @Synchronized
    override fun registerModule(module: DreamDisplaysModule) {
        require(module.id.isNotBlank()) { "Module id must not be blank." }
        require(module.id !in modules) { "Module '${module.id}' is already registered." }
        modules[module.id] = module
        if (started) install(module)
    }

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

    @Synchronized
    override fun stop() {
        if (!started) return
        uninstallInstalled()
        started = false
    }

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

    private fun uninstallInstalled() {
        installed.toList().asReversed().forEach { moduleId ->
            modules[moduleId]?.uninstall(context)
        }
        installed.clear()
    }
}
