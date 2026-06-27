package com.dreamdisplays.util.json

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import org.slf4j.Logger
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Shared atomic JSON file helper for small Dream Displays stores.
 */
class JsonFileStore(
    /** Root directory for store files. */
    val dir: File = File("./config/dreamdisplays"),
) {
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
        if (!file.isFile) return null
        return try {
            DreamJson.pretty.decodeFromString(deserializer, Files.readString(file.toPath(), StandardCharsets.UTF_8))
        } catch (e: IOException) {
            logger.error("Failed to read ${file.name}.", e)
            null
        } catch (e: SerializationException) {
            logger.error("Failed to parse ${file.name}.", e)
            null
        } catch (e: IllegalArgumentException) {
            logger.error("Failed to parse ${file.name}.", e)
            null
        }
    }

    /** Writes [value] to [file] through a temporary file and atomic replace. */
    fun <T> write(file: File, serializer: SerializationStrategy<T>, value: T, logger: Logger) {
        try {
            val parent = file.parentFile ?: dir
            if (!parent.exists() && !parent.mkdirs()) {
                logger.error("Failed to create JSON store directory.")
                return
            }
            val tmp = File(parent, "${file.name}.tmp")
            Files.writeString(
                tmp.toPath(),
                DreamJson.pretty.encodeToString(serializer, value),
                StandardCharsets.UTF_8,
            )
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: IOException) {
            logger.error("Failed to write ${file.name}.", e)
        } catch (e: SerializationException) {
            logger.error("Failed to encode ${file.name}.", e)
        }
    }
}
