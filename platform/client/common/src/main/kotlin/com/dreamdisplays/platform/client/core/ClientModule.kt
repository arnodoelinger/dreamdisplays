package com.dreamdisplays.platform.client.core

import com.dreamdisplays.api.runtime.module.DreamDisplaysModule
import com.dreamdisplays.api.runtime.module.ModuleContext

/**
 * Client module: a self-contained unit of functionality, which can be installed into the client context.
 */
interface ClientModule : DreamDisplaysModule {
    /** Installs this module into the given [context]. This will only be called once, and only after all dependencies have been installed. */
    fun install(context: ClientContext)

    /** Bridges the loader-neutral module contract to the richer client context. */
    override fun install(context: ModuleContext) {
        val clientContext = context as? ClientContext
            ?: error("Client module '$id' requires ClientContext, got ${context::class.java.name}.")
        install(clientContext)
    }

    /** Called when the client lifecycle changes. */
    fun onEvent(event: ClientLifecycleEvent) {}
}
