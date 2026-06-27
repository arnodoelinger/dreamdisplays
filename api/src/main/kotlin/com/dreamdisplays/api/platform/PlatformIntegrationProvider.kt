package com.dreamdisplays.api.platform

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Supplies the [Platform] adapter.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
fun interface PlatformIntegrationProvider {
    /** Creates the platform adapter instance. */
    fun create(): Platform
}
