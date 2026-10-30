package com.dreamdisplays.core.services.keys

import com.dreamdisplays.core.services.DisplayService
import com.dreamdisplays.core.runtime.ServiceKey

/**
 * Display service keys.
 *
 * Modules should prefer these keys over ad-hoc class lookups when depending on public display
 * services.
 */
object DisplayServices {
    /** Public display registry and command surface. */
    val DISPLAY: ServiceKey<DisplayService> =
        ServiceKey("dreamdisplays:display", DisplayService::class.java)
}
