package com.dreamdisplays.platform.server.storage

import com.dreamdisplays.api.storage.PipPinRecord
import com.dreamdisplays.util.json.JsonFileStore
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory

/**
 * JSON persistence for player-to-display Picture-in-Picture pins, so a pinned display's info can be
 * re-sent (bypassing render distance) on the next join. Modeled on [FullscreenSessionStore]: flat,
 * no per-server keying.
 */
object PipPinStore {
    private val logger = LoggerFactory.getLogger("DreamDisplays/PipPinStore")
    private const val SCHEMA_VERSION = 1
    private const val FILE_NAME = "pip-pins.json"
    private val jsonFiles = JsonFileStore()
    private val listSerializer = ListSerializer(PipPinRecord.serializer())

    /** Loads every persisted pin, or an empty list if none are saved yet. */
    fun load(): List<PipPinRecord> =
        jsonFiles.readVersioned(jsonFiles.file(FILE_NAME), listSerializer, SCHEMA_VERSION, logger) ?: emptyList()

    /** Overwrites the store with [records]. */
    fun save(records: List<PipPinRecord>) {
        if (!jsonFiles.ensureDir(logger)) return
        jsonFiles.writeVersioned(jsonFiles.file(FILE_NAME), listSerializer, records, SCHEMA_VERSION, logger)
    }
}
