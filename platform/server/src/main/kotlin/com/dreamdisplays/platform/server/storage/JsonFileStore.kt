package com.dreamdisplays.platform.server.storage

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
 * Shared JSON file helpers for small server-side stores. Keeps the config directory, pretty [Gson],
 * missing-file handling, and atomic write pattern in one place.
 */
internal object JsonFileStore {
    /** Root directory for Dream Displays server JSON files. */
    val dir: File = File("./config/dreamdisplays")

    /** Pretty-printing Gson instance used for human-editable JSON files. */
    val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** Ensures [dir] exists before reads or writes that require it. */
    fun ensureDir(logger: Logger): Boolean {
        if (!dir.exists() && !dir.mkdirs()) {
            logger.error("Failed to create settings directory.")
            return false
        }
        return true
    }

    /** Returns a child file under [dir]. */
    fun file(name: String): File = File(dir, name)

    /** Reads [file] as [type], returning null for missing files and logging real IO errors. */
    fun <T> read(file: File, type: Type, logger: Logger): T? = try {
        FileReader(file).use { gson.fromJson(it, type) }
    } catch (_: FileNotFoundException) {
        null
    } catch (e: IOException) {
        logger.error("Failed to read ${file.name}.", e)
        null
    }

    /** Writes [value] to [file] through a temporary file and atomic replace. */
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
