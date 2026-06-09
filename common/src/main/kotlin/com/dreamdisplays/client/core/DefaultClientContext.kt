package com.dreamdisplays.client.core

import com.dreamdisplays.managers.ClientStateManager
import com.dreamdisplays.platform.api.Platform

/**
 * Default [ClientContext]: the process-wide [DreamServices.registry] and [ClientStateManager],
 * bound to the loader-specific [Platform] the entrypoint registered.
 */
class DefaultClientContext(override val platform: Platform) : ClientContext {
    override val state: ClientMutableState = ClientStateManager
    override val services: ServiceRegistry = DreamServices.registry
}
