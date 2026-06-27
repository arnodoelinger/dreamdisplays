package com.dreamdisplays.api.runtime

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Dream Displays module.
 *
 * Modules should register services, providers, or listeners through [ModuleContext] and avoid
 * depending on concrete platform runtime classes.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
interface DreamDisplaysModule {
    /** Unique module id, preferably in `namespace:name` form. */
    val id: String

    /** Module ids that must be installed before this module. */
    val dependencies: List<String> get() = emptyList()

    /** Installs this module into [context]. Called once after all dependencies are installed. */
    fun install(context: ModuleContext)

    /** Removes runtime hooks registered by this module. Called in reverse install order. */
    fun uninstall(context: ModuleContext) {}
}
