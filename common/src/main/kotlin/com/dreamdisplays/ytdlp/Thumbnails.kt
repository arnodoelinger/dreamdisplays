package com.dreamdisplays.ytdlp

import com.dreamdisplays.Initializer
import com.mojang.blaze3d.platform.NativeImage
import me.inotsleep.utils.logging.LoggingManager
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * Thumbnail manager that handles downloading, caching, and registering YouTube video thumbnails as Minecraft textures.
 * Thumbnails are cached both in memory and on disk (in `config/dreamdisplays/thumb-cache`) with a TTL of 7 days.
 * The cache file names are derived from the video ID, sanitized to be filesystem-safe. Thumbnail downloads and decodes
 * are performed asynchronously to avoid blocking the main thread.
 */
object Thumbnails {
    private val READY = ConcurrentHashMap<String, Identifier>()
    private val IN_FLIGHT = ConcurrentHashMap<String, Boolean>()
    private val COUNTER = AtomicInteger()
    private val THUMB_CACHE_DIR: Path = Path.of("config", "dreamdisplays", "thumb-cache")
    private const val THUMB_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1_000L

    private val EXECUTOR = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() * 2).coerceIn(4, 8)
    ) { r ->
        Thread(r, "DD-Thumbnail-${COUNTER.incrementAndGet()}").apply { isDaemon = true }
    }

    init {
        try {
            ImageIO.scanForPlugins()
        } catch (t: Throwable) {
            LoggingManager.warn("[Thumbnails] ImageIO.scanForPlugins failed: ${t.message}. This should never happen.")
        }
    }


    fun get(videoId: String): Identifier? = READY[videoId]


    fun request(videoId: String, url: String) {
        if (READY.containsKey(videoId)) return
        if (IN_FLIGHT.putIfAbsent(videoId, true) != null) return
        EXECUTOR.submit { download(videoId, url) }
    }

    private fun download(videoId: String, url: String) {
        try {
            readDiskCache(videoId)?.let { bytes ->
                Minecraft.getInstance().execute { register(videoId, bytes) }
                return
            }
            val bytes = fetch(url) ?: error("fetch returned null")
            writeDiskCacheAsync(videoId, bytes)
            Minecraft.getInstance().execute { register(videoId, bytes) }
        } catch (e: Exception) {
            LoggingManager.warn("[Thumbnails] Fetch failed for $videoId: ${e.message}")
            IN_FLIGHT.remove(videoId)
        }
    }

    private fun readDiskCache(videoId: String): ByteArray? = try {
        val f = thumbFile(videoId)
        when {
            !f.isFile -> null
            System.currentTimeMillis() - f.lastModified() > THUMB_CACHE_TTL_MS -> {
                f.delete(); null
            }

            else -> Files.readAllBytes(f.toPath())
        }
    } catch (_: Exception) {
        null
    }

    private fun writeDiskCacheAsync(videoId: String, bytes: ByteArray) {
        EXECUTOR.submit {
            try {
                Files.createDirectories(THUMB_CACHE_DIR)
                val target = thumbFile(videoId)
                val tmp = File(target.parentFile, target.name + ".tmp")
                Files.write(tmp.toPath(), bytes)
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun thumbFile(videoId: String): File {
        val safe = videoId.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]"), "_")
        return File(THUMB_CACHE_DIR.toFile(), "$safe.jpg")
    }

    private fun register(videoId: String, bytes: ByteArray) {
        try {
            val image = decode(bytes)
            val tex = DynamicTexture({ "yt-thumb-$videoId" }, image)
            val safe = videoId.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_")
            val id = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "yt_thumb/$safe")
            Minecraft.getInstance().textureManager.register(id, tex)
            READY[videoId] = id
        } catch (e: IOException) {
            LoggingManager.warn("[Thumbnails] Decode failed for $videoId: ${e.message}")
        } finally {
            IN_FLIGHT.remove(videoId)
        }
    }

    @Throws(IOException::class)
    private fun decode(bytes: ByteArray): NativeImage = ByteArrayInputStream(bytes).use { input ->
        val src: BufferedImage = ImageIO.read(input) ?: run {
            val head = if (bytes.size >= 4)
                String.format(
                    "%02X %02X %02X %02X",
                    bytes[0].toInt() and 0xFF, bytes[1].toInt() and 0xFF,
                    bytes[2].toInt() and 0xFF, bytes[3].toInt() and 0xFF
                )
            else "<empty>"
            throw IOException("[Thumbnails] Unsupported image format (first bytes: $head, size=${bytes.size}).")
        }
        val w = src.width
        val h = src.height
        val image = NativeImage(NativeImage.Format.RGBA, w, h, false)
        val pixels = src.getRGB(0, 0, w, h, null, 0, w)
        val ptr = image.pointer
        for (i in pixels.indices) {
            val argb = pixels[i]
            val abgr = (argb and 0xFF00FF00.toInt()) or
                    ((argb shl 16) and 0x00FF0000) or
                    ((argb shr 16) and 0xFF)
            MemoryUtil.memPutInt(ptr + i.toLong() * 4, abgr)
        }
        image
    }

    private fun fetch(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 8_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0 Dream Displays")
                setRequestProperty("Accept", "image/jpeg,image/png")
            }
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readAllBytes() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
