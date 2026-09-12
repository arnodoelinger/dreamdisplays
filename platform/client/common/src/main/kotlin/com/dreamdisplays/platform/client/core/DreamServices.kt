package com.dreamdisplays.platform.client.core

import com.dreamdisplays.api.runtime.registry.service.ServiceRegistry
import com.dreamdisplays.core.runtime.DefaultServiceRegistry

/**
 * Process-wide [ServiceRegistry] holder.
 */
object DreamServices {
    /** The shared registry. Services are populated once the client application installs its modules. */
    val registry: ServiceRegistry = DefaultServiceRegistry()
}
