package com.dreamdisplays.api.watchparty.service.keys

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.runtime.registry.model.ServiceKey
import com.dreamdisplays.api.runtime.registry.model.serviceKey
import com.dreamdisplays.api.watchparty.service.WatchPartyService

/**
 * Watch party service keys.
 *
 * @since 1.8.x
 */
@Unstable
object WatchPartyServices {
    /** Public Watch party session command surface. */
    val WATCH_PARTY: ServiceKey<WatchPartyService> = serviceKey("dreamdisplays:watch_party")
}
