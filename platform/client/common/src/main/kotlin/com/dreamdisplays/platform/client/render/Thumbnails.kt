package com.dreamdisplays.platform.client.render

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.dreamdisplays.api.media.search.YouTubeUrls
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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import org.slf4j.LoggerFactory
import javax.imageio.ImageIO

/**
 * Thumbnail manager that handles downloading, caching, and registering YouTube video thumbnails as Minecraft textures.
 * Thumbnails are cached both in memory and on disk (in `config/dreamdisplays/thumb-cache`) with a TTL of 7 days.
 * The cache file names are derived by hashing the video ID, keeping case-sensitive IDs distinct. Thumbnail downloads and decodes
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

    /** Average ARGB color of each decoded thumbnail, used as the preview's "ambient" letterbox tint. */
    private val AVG_COLOR: Cache<String, Int> = Caffeine.newBuilder()
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

    /**
     * A missing max-res thumbnail comes back as `HTTP 200` with a tiny grey placeholder rather than
     * a `404`, so it's told apart from a real one by size; real thumbnails (even hqdefault) are
     * always well above this.
     */
    private const val MAXRES_PLACEHOLDER_MAX_BYTES = 4_096

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

    /** Returns the average ARGB color of [videoId]'s thumbnail, or null until it has been decoded. */
    fun averageColor(videoId: String): Int? = AVG_COLOR.getIfPresent(videoId)

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
    private fun loadBytes(videoId: String, fallbackUrl: String): ByteArray {
        readDiskCache(videoId)?.let { return it }
        val bytes = fetchBestAvailable(videoId, fallbackUrl) ?: throw IOException("thumbnail HTTP fetch failed")
        writeDiskCacheAsync(videoId, bytes)
        return bytes
    }

    /** Tries the sharper max-res thumbnail first, falling back to [fallbackUrl] (hqdefault) when it's unavailable. */
    private fun fetchBestAvailable(videoId: String, fallbackUrl: String): ByteArray? {
        val maxRes = fetch(YouTubeUrls.maxResThumbnailUrl(videoId))
        if (maxRes != null && maxRes.size > MAXRES_PLACEHOLDER_MAX_BYTES) return maxRes
        return fetch(fallbackUrl)
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

    /** Returns the cache file for [videoId], hashing the raw ID so case-sensitive IDs never collide. */
    private fun thumbFile(videoId: String): File =
        File(THUMB_CACHE_DIR.toFile(), hash(videoId) + ".jpg")

    /** Returns a SHA-1 hex digest of [s], falling back to `hashCode` if SHA-1 is unavailable. */
    private fun hash(s: String): String = try {
        val md = MessageDigest.getInstance("SHA-1")
        HexFormat.of().formatHex(md.digest(s.toByteArray(StandardCharsets.UTF_8)))
    } catch (_: NoSuchAlgorithmException) {
        Integer.toHexString(s.hashCode())
    }

    /** A decoded thumbnail: the GPU-ready image plus its average color for the ambient preview tint. */
    private class Decoded(val image: NativeImage, val avgColor: Int)

    /** Decodes [bytes] into a [NativeImage], registers it with Minecraft's texture manager, and marks the entry ready. */
    private fun register(videoId: String, bytes: ByteArray) {
        var image: NativeImage? = null
        var tex: DynamicTexture? = null
        try {
            val decoded = decode(bytes)
            image = decoded.image
            //? if >=1.21.11 {
            tex = DynamicTexture({ "yt-thumb-$videoId" }, image)
            //?} else
            /*tex = DynamicTexture(image)*/
            val id = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "yt_thumb/${hash(videoId)}")
            Minecraft.getInstance().textureManager.register(id, tex)
            TextureUploadUtil.applyBilinearFilter(tex)
            AVG_COLOR.put(videoId, decoded.avgColor)
            READY.put(videoId, id)
        } catch (e: Exception) {
            // Runs inside a Minecraft.execute task, so nothing may escape onto the main thread.
            logger.warn("Decode / register failed for $videoId: ${e.message}")
            BYTES.invalidate(videoId)
            // The texture manager owns the texture only once registration succeeds; closing the
            // texture also closes its image, so close the bare image only when no texture exists yet.
            runCatching { tex?.close() ?: image?.close() }
        } finally {
            IN_FLIGHT.invalidate(videoId)
        }
    }

    /** Decodes [bytes] as a JPEG / PNG image into a GPU-ready RGBA [NativeImage] plus its average color. */
    @Throws(IOException::class)
    private fun decode(bytes: ByteArray): Decoded = ByteArrayInputStream(bytes).use { input ->
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
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        for (i in pixels.indices) {
            val argb = pixels[i]
            rSum += (argb ushr 16) and 0xFF
            gSum += (argb ushr 8) and 0xFF
            bSum += argb and 0xFF
            val abgr = (argb and 0xFF00FF00.toInt()) or
                    ((argb shl 16) and 0x00FF0000) or
                    ((argb shr 16) and 0xFF)
            val x = i % w
            val y = i / w
            //? if >=1.21.11 {
            image.setPixelABGR(x, y, abgr)
            //?} else
            /*image.setPixelRGBA(x, y, abgr)*/
        }
        val n = pixels.size.coerceAtLeast(1)
        val avg = (0xFF shl 24) or (((rSum / n).toInt()) shl 16) or (((gSum / n).toInt()) shl 8) or (bSum / n).toInt()
        Decoded(image, avg)
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
