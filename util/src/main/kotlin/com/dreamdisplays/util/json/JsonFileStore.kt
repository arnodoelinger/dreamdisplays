package com.dreamdisplays.util.json

import kotlinx.io.IOException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.*
import org.slf4j.Logger
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Shared atomic JSON file helper for small Dream Displays stores.
 */
class JsonFileStore(
    /** Root directory for store files. */
    val dir: File = File("./config/dreamdisplays"),
) {
    private companion object {
        /** Store file format version. */
        const val SCHEMA_VERSION_FIELD = "schemaVersion"

        /** Store file payload field. */
        const val DATA_FIELD = "data"
    }

    /** Ensures [dir] exists, logging and returning false if it cannot be created. */
    fun ensureDir(logger: Logger): Boolean {
        if (!dir.exists() && !dir.mkdirs()) {
            logger.error("Failed to create JSON store directory.")
            return false
        }
        return true
    }

    /** Resolves [name] against [dir]. */
    fun file(name: String): File = File(dir, name)

    /** Reads and deserializes [file], returning null if the file is absent or unreadable. */
    fun <T> read(file: File, deserializer: DeserializationStrategy<T>, logger: Logger): T? {
        val element = readJsonElement(file, logger) ?: return null
        return decode(file, deserializer, element, logger)
    }

    /**
     * Reads a versioned store file. Files without the version envelope are treated as legacy payloads
     * and decoded directly, so existing user settings migrate on the next save.
     */
    fun <T> readVersioned(
        file: File,
        deserializer: DeserializationStrategy<T>,
        schemaVersion: Int,
        logger: Logger,
    ): T? {
        val element = readJsonElement(file, logger) ?: return null
        val payload = versionedPayload(file, element, schemaVersion, logger) ?: return null
        return decode(file, deserializer, payload, logger)
    }

    /** Writes [value] to [file] through a temporary file and atomic replace. */
    fun <T> write(file: File, serializer: SerializationStrategy<T>, value: T, logger: Logger) {
        val element = runCatching {
            DreamJson.pretty.encodeToJsonElement(serializer, value)
        }.onFailure { e ->
            logger.error("Failed to encode ${file.name}.", e)
        }.getOrNull() ?: return
        writeJsonElement(file, element, logger)
    }

    /** Writes [value] to [file] through a versioned envelope and atomic replace. */
    fun <T> writeVersioned(
        file: File,
        serializer: SerializationStrategy<T>,
        value: T,
        schemaVersion: Int,
        logger: Logger,
    ) {
        val element = runCatching {
            buildJsonObject {
                put(SCHEMA_VERSION_FIELD, JsonPrimitive(schemaVersion))
                put(DATA_FIELD, DreamJson.pretty.encodeToJsonElement(serializer, value))
            }
        }.onFailure { e ->
            logger.error("Failed to encode ${file.name}.", e)
        }.getOrNull() ?: return
        writeJsonElement(file, element, logger)
    }

    /** Read JSON element from a file, returning null if the file is absent or unreadable. */
    private fun readJsonElement(file: File, logger: Logger): JsonElement? {
        if (!file.isFile) return null
        return runCatching {
            DreamJson.pretty.parseToJsonElement(Files.readString(file.toPath(), StandardCharsets.UTF_8))
        }.onFailure { e ->
            val action = if (e is IOException) "read" else "parse"
            logger.error("Failed to $action ${file.name}.", e)
        }.getOrNull()
    }

    /** Decode JSON element from a file, returning null if the file is absent or unreadable. */
    private fun <T> decode(
        file: File,
        deserializer: DeserializationStrategy<T>,
        element: JsonElement,
        logger: Logger,
    ): T? = runCatching {
        DreamJson.pretty.decodeFromJsonElement(deserializer, element)
    }.onFailure { e ->
        logger.error("Failed to parse ${file.name}.", e)
    }.getOrNull()

    /** Versioned payload extraction. */
    private fun versionedPayload(
        file: File,
        element: JsonElement,
        schemaVersion: Int,
        logger: Logger,
    ): JsonElement? {
        val root = element as? JsonObject ?: return element
        if (SCHEMA_VERSION_FIELD !in root) return element
        val payload = root[DATA_FIELD]
        if (payload == null) {
            logger.error("${file.name} is missing a '$DATA_FIELD' payload for its versioned schema.")
            return null
        }
        val fileVersion = root[SCHEMA_VERSION_FIELD]?.jsonPrimitive?.intOrNull ?: 0
        if (fileVersion > schemaVersion) {
            logger.error(
                "${file.name} uses schema version $fileVersion, but this build supports version $schemaVersion.",
            )
            return null
        }
        return payload
    }

    /** Write JSON element to a file through a temporary file and atomic replace. */
    private fun writeJsonElement(file: File, element: JsonElement, logger: Logger) {
        val parent = file.parentFile ?: dir
        if (!parent.exists() && !parent.mkdirs()) {
            logger.error("Failed to create JSON store directory.")
            return
        }
        runCatching {
            val tmp = File(parent, "${file.name}.tmp")
            Files.writeString(
                tmp.toPath(),
                DreamJson.pretty.encodeToString(JsonElement.serializer(), element),
                StandardCharsets.UTF_8,
            )
            replace(tmp, file)
        }.onFailure { e ->
            val action = if (e is IOException) "write" else "encode"
            logger.error("Failed to $action ${file.name}.", e)
        }
    }

    /** Atomic replace of a file, falling back to a non-atomic move where the filesystem can't do atomic replace. */
    private fun replace(tmp: File, target: File) {
        runCatching {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching { e ->
            if (e !is AtomicMoveNotSupportedException) throw e
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }
}
