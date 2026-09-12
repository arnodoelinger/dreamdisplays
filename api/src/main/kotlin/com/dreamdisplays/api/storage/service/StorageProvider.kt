package com.dreamdisplays.api.storage.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.model.settings.ClientSettingsStorage

/**
 * Supplies the storage backends.
 *
 * @since 1.8.x
 */
@Unstable
interface StorageProvider {
    /** The server-authoritative display snapshot registry. */
    fun displayStorage(): DisplayStorageService

    /** The client-local per-display settings store. */
    fun clientSettingsStorage(): ClientSettingsStorage
}
