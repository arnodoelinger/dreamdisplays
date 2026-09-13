package com.dreamdisplays.platform.client.storage

import com.dreamdisplays.api.display.model.settings.ClientSettingsStorage
import com.dreamdisplays.api.storage.service.DisplayStorageService
import com.dreamdisplays.api.storage.service.StorageProvider
import com.dreamdisplays.core.services.DisplayStorage as CoreDisplayStorage

/** Supplies the client's storage backends: the core display snapshot registry and the JSON settings store. */
object DefaultStorageProvider : StorageProvider {
    override fun displayStorage(): DisplayStorageService = CoreDisplayStorage
    override fun clientSettingsStorage(): ClientSettingsStorage = ClientSettingsStore
}
