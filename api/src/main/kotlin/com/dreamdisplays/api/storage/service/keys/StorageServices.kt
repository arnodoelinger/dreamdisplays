package com.dreamdisplays.api.storage.service.keys

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.model.settings.ClientSettingsStorage
import com.dreamdisplays.api.runtime.registry.model.ServiceKey
import com.dreamdisplays.api.runtime.registry.model.serviceKey
import com.dreamdisplays.api.storage.service.DisplayStorageService

/**
 * Storage service keys.
 *
 * @since 1.8.x
 */
@Unstable
object StorageServices {
    /** Server-authoritative display snapshot registry. */
    val DISPLAY_STORAGE: ServiceKey<DisplayStorageService> = serviceKey("dreamdisplays:display_storage")

    /** Client-local per-display settings store. */
    val CLIENT_SETTINGS: ServiceKey<ClientSettingsStorage> = serviceKey("dreamdisplays:client_settings_storage")
}
