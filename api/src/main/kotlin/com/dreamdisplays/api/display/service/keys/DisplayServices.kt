package com.dreamdisplays.api.display.service.keys

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.service.DisplayService
import com.dreamdisplays.api.runtime.registry.model.ServiceKey
import com.dreamdisplays.api.runtime.registry.model.serviceKey

/**
 * Display service keys. Modules should prefer these keys over ad-hoc class lookups when depending on public display
 * services.
 *
 * @since 1.8.x
 */
@Unstable
object DisplayServices {
    /** Public display registry and command surface. */
    val DISPLAY: ServiceKey<DisplayService> = serviceKey("dreamdisplays:display")
}
