package com.dreamdisplays.platform.client.render

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.util.AsyncMemo
import com.dreamdisplays.util.DreamCoroutines
import com.dreamdisplays.util.net.DreamHttpClient
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import org.slf4j.LoggerFactory
import javax.imageio.ImageIO

/**
 * Thumbnail manager that handles downloading, caching, and registering YouTube video thumbnails as Minecraft textures.
 * Thumbnails are cached both in memory and on disk (in `config/dreamdisplays/thumb-cache`) with a TTL of 7 days.
 * The cache file names are derived from the video ID, sanitized to be filesystem-safe. Thumbnail downloads and decodes
 * are performed asynchronously to avoid blocking the main thread.
 */
object Thumbnails {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/Thumbnails")

    /** The Minecraft texture [Identifier] for each video ID, or null if not yet loaded. */
    private val READY: Cache<String, Identifier> = Caffeine.newBuilder()
        .maximumSize(1_024)
        .expireAfterAccess(6, TimeUnit.HOURS)
        .build()

    /** Tracks which video IDs are currently in flight (downloading or decoding). */
    private val IN_FLIGHT: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(512)
        .expireAfterWrite(2, TimeUnit.MINUTES)
        .build()

    /** Deduplicates thumbnail byte loads and keeps recently used compressed bytes warm. */
    private val BYTES = AsyncMemo<String, ByteArray>(
        maxSize = 512,
        ttlMs = 30L * 60L * 1_000L,
        scope = DreamCoroutines.clientIo,
        tag = "thumbnail",
    )

    /** Directory for cached thumbnails. */
    private val THUMB_CACHE_DIR: Path = Path.of("config", "dreamdisplays", "thumb-cache")

    /** TTL for cached thumbnails, in milliseconds. */
    private const val THUMB_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1_000L

    /** Initializes the thumbnail manager and scans for image plugins. */
    init {
        try {
            ImageIO.scanForPlugins()
        } catch (t: Throwable) {
            logger.warn("ImageIO.scanForPlugins failed: ${t.message}. This should never happen.")
        }
    }

    /** Returns the registered Minecraft texture [Identifier] for [videoId], or null if not yet loaded. */
    fun get(videoId: String): Identifier? = READY.getIfPresent(videoId)

    /** Schedules a background download of the thumbnail at [url] for [videoId] if not already in flight or ready. */
    fun request(videoId: String, url: String) {
        if (READY.getIfPresent(videoId) != null) return
        if (IN_FLIGHT.asMap().putIfAbsent(videoId, true) != null) return
        DreamCoroutines.clientIo.launch { download(videoId, loadBytesAsync(videoId, url)) }
    }

    /** Starts or joins the thumbnail byte load for [videoId]. */
    private fun loadBytesAsync(videoId: String, url: String): Deferred<ByteArray> =
        BYTES.load(videoId) { loadBytes(it, url) }

    /** Fetches the thumbnail bytes for [videoId] from memory, disk, or network. */
    private fun loadBytes(videoId: String, url: String): ByteArray {
        readDiskCache(videoId)?.let { return it }
        val bytes = fetch(url) ?: throw IOException("thumbnail HTTP fetch failed")
        writeDiskCacheAsync(videoId, bytes)
        return bytes
    }

    /** Awaits the thumbnail bytes and registers them on the render thread. */
    private suspend fun download(videoId: String, bytesDeferred: Deferred<ByteArray>) {
        try {
            val bytes = bytesDeferred.await()
            Minecraft.getInstance().execute { register(videoId, bytes) }
        } catch (e: Exception) {
            logger.warn("Fetch failed for $videoId: ${e.message}")
            BYTES.invalidate(videoId)
            IN_FLIGHT.invalidate(videoId)
        }
    }

    /** Reads the cached thumbnail bytes for [videoId] from disk; returns null if absent or expired. */
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

    /** Atomically writes [bytes] to the disk cache for [videoId] via a temp-file rename in the background. */
    private fun writeDiskCacheAsync(videoId: String, bytes: ByteArray) {
        DreamCoroutines.clientIo.launch {
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

    /** Returns the cache file for [videoId], using a sanitized lowercase filename. */
    private fun thumbFile(videoId: String): File {
        val safe = videoId.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]"), "_")
        return File(THUMB_CACHE_DIR.toFile(), "$safe.jpg")
    }

    /** Decodes [bytes] into a [NativeImage], registers it with Minecraft's texture manager, and marks the entry ready. */
    private fun register(videoId: String, bytes: ByteArray) {
        try {
            val image = decode(bytes)
            //? if >=1.21.11 {
            val tex = DynamicTexture({ "yt-thumb-$videoId" }, image)
            //?} else
            /*val tex = DynamicTexture(image)*/
            val safe = videoId.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_")
            val id = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "yt_thumb/$safe")
            Minecraft.getInstance().textureManager.register(id, tex)
            READY.put(videoId, id)
        } catch (e: IOException) {
            logger.warn("Decode failed for $videoId: ${e.message}")
            BYTES.invalidate(videoId)
        } finally {
            IN_FLIGHT.invalidate(videoId)
        }
    }

    /** Decodes [bytes] as a JPEG / PNG image and converts it to an RGBA [NativeImage] suitable for OpenGL upload. */
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
            throw IOException("Unsupported image format (first bytes: $head, size=${bytes.size}).")
        }
        val w = src.width
        val h = src.height
        val image = NativeImage(NativeImage.Format.RGBA, w, h, false)
        val pixels = src.getRGB(0, 0, w, h, null, 0, w)
        for (i in pixels.indices) {
            val argb = pixels[i]
            val abgr = (argb and 0xFF00FF00.toInt()) or
                    ((argb shl 16) and 0x00FF0000) or
                    ((argb shr 16) and 0xFF)
            val x = i % w
            val y = i / w
            //? if >=1.21.11 {
            image.setPixelABGR(x, y, abgr)
            //?} else
            /*image.setPixelRGBA(x, y, abgr)*/
        } // TODO: !!
        image
    }

    /** Downloads raw image bytes from [url]; returns null on any HTTP or network error. */
    private fun fetch(url: String): ByteArray? {
        return try {
            val response = DreamHttpClient.execute(
                url,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf(
                        "User-Agent" to "Mozilla/5.0 Dream Displays",
                        "Accept" to "image/jpeg,image/png",
                    ),
                    connectTimeoutMs = 8_000,
                    readTimeoutMs = 15_000,
                ),
            )
            if (response.code != 200) null else response.body
        } catch (_: Exception) {
            null
        }
    }
}
