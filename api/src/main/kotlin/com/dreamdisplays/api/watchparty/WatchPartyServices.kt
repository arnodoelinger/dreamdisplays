package com.dreamdisplays.api.watchparty

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.runtime.ServiceKey
import com.dreamdisplays.api.runtime.serviceKey

/**
 * Watch party service keys.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
object WatchPartyServices {
    /** Public Watch party session command surface. */
    val WATCH_PARTY: ServiceKey<WatchPartyService> = serviceKey("dreamdisplays:watch_party")
}
