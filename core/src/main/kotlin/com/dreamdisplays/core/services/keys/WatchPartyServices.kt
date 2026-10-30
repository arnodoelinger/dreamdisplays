package com.dreamdisplays.core.services.keys

import com.dreamdisplays.core.runtime.ServiceKey
import com.dreamdisplays.api.watchparty.WatchPartyService

/** Watch party service keys. */
object WatchPartyServices {
    /** Public `Watch party` session command surface. */
    val WATCH_PARTY: ServiceKey<WatchPartyService> =
        ServiceKey("dreamdisplays:watch_party", WatchPartyService::class.java)
}
