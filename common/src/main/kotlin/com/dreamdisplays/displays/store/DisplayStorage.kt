package com.dreamdisplays.displays.store

import java.util.UUID

/**
 * Thin lifecycle facade over [ClientSettingsStore] and [ServerDisplayStore] for the cross-cutting operations
 * that span both stores. Focused, single-store work should call the relevant store directly.
 */
object DisplayStorage {
    /** Loads client-local display settings at startup. */
    fun load() = ClientSettingsStore.load()

    /** Fully forgets [displayUuid]: removes it from every server registry and from the client settings. */
    fun removeDisplay(displayUuid: UUID) {
        ServerDisplayStore.remove(displayUuid)
        ClientSettingsStore.remove(displayUuid)
    }
}
