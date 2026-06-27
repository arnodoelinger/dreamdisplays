package com.dreamdisplays.api.platform

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.runtime.ServiceKey
import com.dreamdisplays.api.runtime.serviceKey

/**
 * Platform service keys.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
object PlatformServices {
    /** Platform service. */
    val PLATFORM: ServiceKey<Platform> = serviceKey("dreamdisplays:platform")
}
