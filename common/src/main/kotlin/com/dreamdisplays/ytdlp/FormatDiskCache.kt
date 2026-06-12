package com.dreamdisplays.ytdlp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.slf4j.LoggerFactory
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
    private val logger = LoggerFactory.getLogger("DreamDisplays/FormatDiskCache")
    private val CACHE_DIR: Path = Path.of("config", "dreamdisplays", "yt-cache")
    private val GSON: Gson = GsonBuilder().create()
    private val STREAM_LIST_TYPE = object : TypeToken<List<YtStream>>() {}.type
    private const val DEFAULT_TTL_MS = 5L * 60L * 60L * 1_000L
    private const val SCHEMA_VERSION = 3

    private val WRITER = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DD-FormatCache-writer").apply { isDaemon = true }
    }

    /** Reads the cached streams for [videoUrl] from disk; returns null if absent, expired, or schema-mismatched. */
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

    /** Serialises [streams] to disk for [videoUrl] asynchronously via the single-threaded writer executor. */
    fun saveAsync(videoUrl: String, streams: List<YtStream>) {
        if (streams.isEmpty()) return
        WRITER.submit { writeNow(videoUrl, streams) }
    }

    /** Atomically writes the stream JSON to disk using a temp file and rename. */
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
            logger.warn("Write failed: ${e.message}")
        }
    }

    /** Deletes the cache entry for [videoUrl] asynchronously. */
    fun deleteEntry(videoUrl: String) {
        WRITER.submit {
            runCatching { Files.deleteIfExists(fileFor(videoUrl).toPath()) }
        }
    }

    /** Scans the cache directory and deletes all `.json` entries older than [maxAgeMs] milliseconds. */
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

    /** Returns the cache file path for [videoUrl] by hashing the URL to a stable filename. */
    private fun fileFor(videoUrl: String): File = File(CACHE_DIR.toFile(), hash(videoUrl) + ".json")

    /** Returns a SHA-1 hex digest of [s], falling back to `hashCode` if SHA-1 is unavailable. */
    private fun hash(s: String): String = try {
        val md = MessageDigest.getInstance("SHA-1")
        HexFormat.of().formatHex(md.digest(s.toByteArray(StandardCharsets.UTF_8)))
    } catch (_: NoSuchAlgorithmException) {
        Integer.toHexString(s.hashCode())
    }
}
