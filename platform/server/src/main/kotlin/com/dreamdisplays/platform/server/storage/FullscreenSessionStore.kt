package com.dreamdisplays.platform.server.storage

import com.dreamdisplays.api.storage.FullscreenSessionRecord
import com.dreamdisplays.util.json.JsonFileStore
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory

/**
 * JSON persistence for non-transient fullscreen broadcast sessions, so they survive a server
 * restart. Modeled on [ServerDisplayStore]'s versioned envelope, but flat (no per-server keying) -
 * fullscreen sessions aren't part of a display's own record.
 */
object FullscreenSessionStore {
    private val logger = LoggerFactory.getLogger("DreamDisplays/FullscreenSessionStore")
    private const val SCHEMA_VERSION = 1
    private const val FILE_NAME = "fullscreen-sessions.json"
    private val jsonFiles = JsonFileStore()
    private val listSerializer = ListSerializer(FullscreenSessionRecord.serializer())

    /** Loads every persisted session record, or an empty list if none are saved yet. */
    fun load(): List<FullscreenSessionRecord> =
        jsonFiles.readVersioned(jsonFiles.file(FILE_NAME), listSerializer, SCHEMA_VERSION, logger) ?: emptyList()

    /** Overwrites the store with [records]. */
    fun save(records: List<FullscreenSessionRecord>) {
        if (!jsonFiles.ensureDir(logger)) return
        jsonFiles.writeVersioned(jsonFiles.file(FILE_NAME), listSerializer, records, SCHEMA_VERSION, logger)
    }
}
