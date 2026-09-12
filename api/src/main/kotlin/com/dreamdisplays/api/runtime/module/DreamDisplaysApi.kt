package com.dreamdisplays.api.runtime.module

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.runtime.registry.service.ServiceRegistry

/**
 * Entry point for services exposed to integrations and modules.
 *
 * @since 1.8.x
 */
@Unstable
interface DreamDisplaysApi {
    /** Contract-typed services available in the current runtime. */
    val services: ServiceRegistry
}
