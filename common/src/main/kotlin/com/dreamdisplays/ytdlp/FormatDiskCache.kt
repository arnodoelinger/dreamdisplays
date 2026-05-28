package com.dreamdisplays.ytdlp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import me.inotsleep.utils.logging.LoggingManager
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.concurrent.Executors

/**
 * Persistent on-disk cache for resolved YouTube format URLs.
 */
object FormatDiskCache {
    private val CACHE_DIR: Path = Path.of("config", "dreamdisplays", "yt-cache")
    private val GSON: Gson = GsonBuilder().create()
    private val STREAM_LIST_TYPE = object : TypeToken<List<YtStream>>() {}.type
    private const val DEFAULT_TTL_MS = 5L * 60L * 60L * 1_000L
    private const val SCHEMA_VERSION = 2

    private val WRITER = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DD-FormatCache-writer").apply { isDaemon = true }
    }

    fun load(videoUrl: String, maxAgeMs: Long): List<YtStream>? {
        val f = fileFor(videoUrl)
        if (!f.isFile) return null
        return try {
            val json = Files.readString(f.toPath(), StandardCharsets.UTF_8)
            val obj = JsonParser.parseString(json).asJsonObject
            val version = if (obj.has("v")) obj.get("v").asInt else 0
            if (version != SCHEMA_VERSION) {
                f.delete()
                return null
            }
            val ts = if (obj.has("ts")) obj.get("ts").asLong else 0L
            if (System.currentTimeMillis() - ts > maxAgeMs) {
                f.delete()
                return null
            }
            val streams: List<YtStream>? = GSON.fromJson(obj.get("streams"), STREAM_LIST_TYPE)
            if (streams.isNullOrEmpty()) null else streams
        } catch (_: Exception) {
            runCatching { f.delete() }
            null
        }
    }

    fun saveAsync(videoUrl: String, streams: List<YtStream>) {
        if (streams.isEmpty()) return
        WRITER.submit { writeNow(videoUrl, streams) }
    }

    private fun writeNow(videoUrl: String, streams: List<YtStream>) {
        try {
            Files.createDirectories(CACHE_DIR)
            val target = fileFor(videoUrl)
            val tmp = File(target.parentFile, target.name + ".tmp")
            val root = JsonObject().apply {
                addProperty("v", SCHEMA_VERSION)
                addProperty("ts", System.currentTimeMillis())
                addProperty("url", videoUrl)
                add("streams", GSON.toJsonTree(streams, STREAM_LIST_TYPE))
            }
            Files.writeString(tmp.toPath(), GSON.toJson(root), StandardCharsets.UTF_8)
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: IOException) {
            LoggingManager.warn("[FormatDiskCache] Write failed: ${e.message}")
        }
    }

    fun deleteEntry(videoUrl: String) {
        WRITER.submit {
            runCatching { Files.deleteIfExists(fileFor(videoUrl).toPath()) }
        }
    }

    fun sweepExpired(maxAgeMs: Long = DEFAULT_TTL_MS) {
        try {
            if (!Files.isDirectory(CACHE_DIR)) return
            val now = System.currentTimeMillis()
            Files.list(CACHE_DIR).use { stream ->
                stream.filter { it.toString().endsWith(".json") }.forEach { p ->
                    try {
                        val json = Files.readString(p, StandardCharsets.UTF_8)
                        val obj = JsonParser.parseString(json).asJsonObject
                        val ts = if (obj.has("ts")) obj.get("ts").asLong else 0L
                        if (now - ts > maxAgeMs) Files.deleteIfExists(p)
                    } catch (_: Exception) {
                        runCatching { Files.deleteIfExists(p) }
                    }
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun fileFor(videoUrl: String): File = File(CACHE_DIR.toFile(), hash(videoUrl) + ".json")

    private fun hash(s: String): String = try {
        val md = MessageDigest.getInstance("SHA-1")
        HexFormat.of().formatHex(md.digest(s.toByteArray(StandardCharsets.UTF_8)))
    } catch (_: NoSuchAlgorithmException) {
        Integer.toHexString(s.hashCode())
    }
}
