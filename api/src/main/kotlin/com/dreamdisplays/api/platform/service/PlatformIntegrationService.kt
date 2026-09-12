package com.dreamdisplays.api.platform.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.platform.identity.Platform

/**
 * Supplies the [Platform] adapter.
 *
 * @since 1.8.x
 */
@Unstable
fun interface PlatformIntegrationService {
    /** Creates the platform adapter instance. */
    fun create(): Platform
}
