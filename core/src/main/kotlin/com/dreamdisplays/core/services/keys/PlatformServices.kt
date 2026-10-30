package com.dreamdisplays.core.services.keys

import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.core.runtime.ServiceKey

/** Platform service keys. */
object PlatformServices {
    /** Platform service. */
    val PLATFORM: ServiceKey<Platform> =
        ServiceKey("dreamdisplays:platform", Platform::class.java)
}
