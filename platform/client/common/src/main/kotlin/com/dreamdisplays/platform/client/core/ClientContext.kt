package com.dreamdisplays.platform.client.core

import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.api.runtime.module.ModuleContext
import com.dreamdisplays.api.runtime.registry.service.ServiceRegistry

/**
 * Context for a client application.
 */
interface ClientContext : ModuleContext {
    /** Mutable state of the client, which can be updated by modules and observed by other modules. */
    val state: ClientMutableState

    /** Registry of services available to the client, which modules can use to provide and consume services. */
    override val services: ServiceRegistry

    /** Platform-specific APIs and information, which modules can use to interact with the underlying platform. */
    val platform: Platform
}
