package com.dreamdisplays.api.runtime.module

import com.dreamdisplays.api.Unstable

/**
 * Assembler.
 *
 * @since 1.8.x
 */
@Unstable
interface DreamDisplaysRuntime : DreamDisplaysApi, AutoCloseable {
    /** Registered module ids in registration order. */
    val registeredModuleIds: Set<String>

    /** Installed module ids in install order. */
    val installedModuleIds: Set<String>

    /** Registers [module]. If the runtime is already started, the module is installed immediately. */
    fun registerModule(module: DreamDisplaysModule)

    /** Installs all registered modules in dependency order. */
    fun start()

    /** Uninstalls installed modules in reverse install order. */
    fun stop()

    /** Alias for [stop], allowing runtimes to be used with `use`. */
    override fun close() {
        stop()
    }
}
