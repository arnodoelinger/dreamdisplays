package com.dreamdisplays.api.display.service

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.runtime.ServiceKey
import com.dreamdisplays.api.runtime.serviceKey

/**
 * Display service keys. Modules should prefer these keys over ad-hoc class lookups when depending on public display
 * services.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
object DisplayServices {
    /** Public display registry and command surface. */
    val DISPLAY: ServiceKey<DisplayService> = serviceKey("dreamdisplays:display")
}
