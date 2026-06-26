package com.dreamdisplays.platform.server.storage

import java.util.Locale

/** Supported SQL storage backends from the `storage.type` config value. */
enum class StorageBackend {
    SQLITE,
    MYSQL;

    val configToken: String get() = name
    val metricToken: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun fromConfig(raw: String): StorageBackend {
            val token = raw.trim().uppercase(Locale.ROOT)
            return entries.firstOrNull { it.name == token }
                ?: error("Unsupported storage type: $raw.")
        }
    }
}
