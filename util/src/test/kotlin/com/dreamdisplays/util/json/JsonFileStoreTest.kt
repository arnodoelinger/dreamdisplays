package com.dreamdisplays.util.json

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFileStoreTest {
    private val logger = LoggerFactory.getLogger(JsonFileStoreTest::class.java)
    private val mapSerializer = MapSerializer(String.serializer(), Int.serializer())

    @Test
    fun writesAndReadsVersionedEnvelope() {
        val dir = Files.createTempDirectory("dreamdisplays-json-store").toFile()
        try {
            val store = JsonFileStore(dir)
            val file = store.file("store.json")

            store.writeVersioned(file, mapSerializer, mapOf("display" to 7), schemaVersion = 2, logger)

            val json = file.readText()
            assertTrue(json.contains("\"schemaVersion\": 2"))
            assertTrue(json.contains("\"data\""))
            assertEquals(
                mapOf("display" to 7),
                store.readVersioned(file, mapSerializer, schemaVersion = 2, logger),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readsLegacyPayloadWithoutEnvelope() {
        val dir = Files.createTempDirectory("dreamdisplays-json-store").toFile()
        try {
            val store = JsonFileStore(dir)
            val file = store.file("store.json")
            file.writeText("""{"data":7}""")

            assertEquals(
                mapOf("data" to 7),
                store.readVersioned(file, mapSerializer, schemaVersion = 1, logger),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsNewerSchemaVersion() {
        val dir = Files.createTempDirectory("dreamdisplays-json-store").toFile()
        try {
            val store = JsonFileStore(dir)
            val file = store.file("store.json")
            file.writeText("""{"schemaVersion":99,"data":{"display":7}}""")

            assertNull(store.readVersioned(file, mapSerializer, schemaVersion = 1, logger))
        } finally {
            dir.deleteRecursively()
        }
    }
}
