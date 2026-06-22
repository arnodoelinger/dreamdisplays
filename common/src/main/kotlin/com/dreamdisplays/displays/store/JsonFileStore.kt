package com.dreamdisplays.displays.store

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.slf4j.Logger
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.IOException
import java.lang.reflect.Type
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Shared plumbing for the JSON-backed display stores: the config directory, a pretty-printing [Gson]
 * instance, and read/write helpers that swallow the expected "file not found" case while logging real IO errors.
 */
internal object JsonFileStore {
    /** Root directory for all Dream Displays config files. */
    val dir: File = File("./config/dreamdisplays")

    /** Pretty-printing Gson shared by every store. */
    val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** Ensures [dir] exists, logging and returning false if it cannot be created. */
    fun ensureDir(logger: Logger): Boolean {
        if (!dir.exists() && !dir.mkdirs()) {
            logger.error("Failed to create settings directory.")
            return false
        }
        return true
    }

    /** Resolves [name] against the config [dir]. */
    fun file(name: String): File = File(dir, name)

    /** Reads and deserializes [file] as [type], returning null if the file is absent or unreadable. */
    fun <T> read(file: File, type: Type, logger: Logger): T? = try {
        FileReader(file).use { gson.fromJson(it, type) }
    } catch (_: FileNotFoundException) {
        null
    } catch (e: IOException) {
        logger.error("Failed to read ${file.name}.", e)
        null
    }

    /**
     * Serializes [value] to [file] via a temp file and atomic rename, so a crash mid-write can
     * never leave a truncated / corrupt [file] behind. Logs any IO error.
     */
    fun write(file: File, value: Any, logger: Logger) {
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            Files.writeString(tmp.toPath(), gson.toJson(value), StandardCharsets.UTF_8)
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: IOException) {
            logger.error("Failed to write ${file.name}.", e)
        }
    }
}
