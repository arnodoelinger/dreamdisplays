package com.dreamdisplays.api.runtime

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Entry point for services exposed to integrations and modules.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
interface DreamDisplaysApi {
    /** Contract-typed services available in the current runtime. */
    val services: ServiceRegistry
}
