package com.dreamdisplays.platform.client.platform

import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.api.platform.service.PlatformIntegrationService

/** Supplies the `NeoForge` [Platform] adapter. */
object NeoForgePlatformIntegrationProvider : PlatformIntegrationService {
    override fun create(): Platform = NeoForgePlatform
}
