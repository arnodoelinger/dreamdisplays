package com.dreamdisplays.api.platform.service.keys

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.api.runtime.registry.model.ServiceKey
import com.dreamdisplays.api.runtime.registry.model.serviceKey

/**
 * Platform service keys.
 *
 * @since 1.8.x
 */
@Unstable
object PlatformServices {
    /** Platform service. */
    val PLATFORM: ServiceKey<Platform> = serviceKey("dreamdisplays:platform")
}
