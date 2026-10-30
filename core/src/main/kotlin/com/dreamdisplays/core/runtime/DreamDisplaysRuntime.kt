package com.dreamdisplays.core.runtime

/** `Dream Displays`' assembler. */
interface DreamDisplaysRuntime : AutoCloseable {
    /** Registry of services shared by all modules. */
    val services: ServiceRegistry

    /** Registered module IDs in registration order. */
    val registeredModuleIds: Set<String>

    /** Installed module IDs in install order. */
    val installedModuleIds: Set<String>

    /**
     * Registers [module].
     *
     * If the runtime is already started, the module is installed immediately.
     */
    fun registerModule(module: DreamDisplaysModule)

    /** Installs all registered modules in dependency order. */
    fun start()

    /** Uninstalls installed modules in reverse install order. */
    fun stop()

    override fun close() {
        stop()
    }
}
