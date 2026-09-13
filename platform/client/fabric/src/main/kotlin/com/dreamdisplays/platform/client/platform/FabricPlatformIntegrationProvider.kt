package com.dreamdisplays.platform.client.platform

import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.api.platform.service.PlatformIntegrationService

/** Supplies the `Fabric` [Platform] adapter. */
object FabricPlatformIntegrationProvider : PlatformIntegrationService {
    override fun create(): Platform = FabricPlatform
}
