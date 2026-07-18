package com.dreamdisplays.platform.client.render

//? if >=1.21.11 {
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplays.api.media.search.YouTubeUrls
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.util.AsyncMemo
import com.dreamdisplays.util.DreamCoroutines
import com.dreamdisplays.util.net.DreamHttpClient
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.mojang.blaze3d.platform.NativeImage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.coroutines.cancellation.CancellationException

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

    /** Average ARGB color of each decoded thumbnail, used for small flat accents (e.g. card hover tint). */
    private val AVG_COLOR: Cache<String, Int> = Caffeine.newBuilder()
        .maximumSize(1_024)
        .expireAfterAccess(6, TimeUnit.HOURS)
        .build()

    /**
     * The Minecraft texture [Identifier] of a tiny, heavily downsampled copy of each decoded
     * thumbnail — a YouTube-style blurred "ambient" backdrop rendered by stretching it across the
     * preview's letterbox area with bilinear filtering, instead of a flat average-color fill.
     */
    private val AMBIENT_TEX: Cache<String, Identifier> = Caffeine.newBuilder()
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

    /** Requested thumbnail resolution tier: [HIGH] for the big preview, [LOW] for the small cards. */
    enum class Quality { HIGH, LOW }

    /** Composite cache key so [Quality.HIGH] (preview) and [Quality.LOW] (cards) never overwrite each other. */
    private fun key(videoId: String, quality: Quality): String =
        if (quality == Quality.HIGH) videoId else "$videoId@lq"

    /** Returns the registered Minecraft texture [Identifier] for [videoId] at [quality], or null if not loaded. */
    fun get(videoId: String, quality: Quality = Quality.HIGH): Identifier? = READY.getIfPresent(key(videoId, quality))

    /** Returns the average ARGB color of [videoId]'s thumbnail, or null until it has been decoded. */
    fun averageColor(videoId: String): Int? = AVG_COLOR.getIfPresent(videoId)

    /** Returns the blurred ambient-backdrop texture [Identifier] for [videoId], or null until decoded. */
    fun ambientTexture(videoId: String): Identifier? = AMBIENT_TEX.getIfPresent(videoId)

    /** Schedules a background download of [videoId]'s thumbnail at [quality] if not already in flight or ready. */
    fun request(videoId: String, quality: Quality = Quality.HIGH) {
        val k = key(videoId, quality)
        if (READY.getIfPresent(k) != null) return
        if (IN_FLIGHT.asMap().putIfAbsent(k, true) != null) return
        DreamCoroutines.clientIo.launch {
            download(videoId, k, loadBytesAsync(k) { fetchForQuality(videoId, quality) })
        }
    }

    /**
     * Schedules a background download of the image at [directUrl], cached / registered under [key]
     * (e.g. a Twitch composite key) instead of deriving a YouTube thumbnail URL from an id. Used for
     * sources (like Twitch) whose thumbnail URL is already resolved, not just an id to derive one from.
     */
    fun request(key: String, directUrl: String) {
        if (READY.getIfPresent(key) != null) return
        if (IN_FLIGHT.asMap().putIfAbsent(key, true) != null) return
        DreamCoroutines.clientIo.launch { download(key, key, loadBytesAsync(key) { fetch(directUrl) }) }
    }

    /** Starts or joins the thumbnail byte load under [key], fetching via [fetchBytes] on a cache miss. */
    private fun loadBytesAsync(key: String, fetchBytes: () -> ByteArray?): Deferred<ByteArray> =
        BYTES.load(key) { loadBytes(key, fetchBytes) }

    /** Fetches the thumbnail bytes for [key] from memory, disk, or network (via [fetchBytes]). */
    private fun loadBytes(key: String, fetchBytes: () -> ByteArray?): ByteArray {
        readDiskCache(key)?.let { return it }
        val bytes = fetchBytes() ?: throw IOException("thumbnail HTTP fetch failed")
        writeDiskCacheAsync(key, bytes)
        return bytes
    }

    /**
     * Fetches bytes for the requested tier. [Quality.LOW] pulls the compact 320x180 mqdefault (plenty
     * for the small suggestion cards); [Quality.HIGH] tries the sharp maxresdefault and falls back to
     * mqdefault when it's missing. hqdefault is deliberately avoided — it's 4:3 with black bars,
     * unlike the clean 16:9 mq / maxres.
     */
    private fun fetchForQuality(videoId: String, quality: Quality): ByteArray? = when (quality) {
        Quality.LOW -> fetch(YouTubeUrls.mqThumbnailUrl(videoId))
        Quality.HIGH -> {
            val maxRes = fetch(YouTubeUrls.maxResThumbnailUrl(videoId))
            if (maxRes != null && maxRes.size > MAXRES_PLACEHOLDER_MAX_BYTES) maxRes
            else fetch(YouTubeUrls.mqThumbnailUrl(videoId))
        }
    }

    /** Awaits the thumbnail bytes and registers them on the render thread. */
    private suspend fun download(avgColorKey: String, cacheKey: String, bytesDeferred: Deferred<ByteArray>) {
        runCatching { bytesDeferred.await() }
            .onSuccess { bytes ->
                Minecraft.getInstance().execute { register(avgColorKey, cacheKey, bytes) }
            }
            .onFailure { e ->
                if (e is CancellationException) throw e

                logger.warn("Fetch failed for $cacheKey: ${e.message}.")
                BYTES.invalidate(cacheKey)
                IN_FLIGHT.invalidate(cacheKey)
            }
    }

    /** Reads the cached thumbnail bytes for [cacheKey] from disk; returns null if absent or expired. */
    private fun readDiskCache(cacheKey: String): ByteArray? = try {
        val f = thumbFile(cacheKey)
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

    /** Atomically writes [bytes] to the disk cache for [cacheKey] via a temp-file rename in the background. */
    private fun writeDiskCacheAsync(cacheKey: String, bytes: ByteArray) {
        DreamCoroutines.clientIo.launch {
            try {
                Files.createDirectories(THUMB_CACHE_DIR)
                val target = thumbFile(cacheKey)
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

    /** Returns the cache file for [cacheKey], hashing it so different keys never collide. */
    private fun thumbFile(cacheKey: String): File =
        File(THUMB_CACHE_DIR.toFile(), hash(cacheKey) + ".jpg")

    /** Returns a SHA-1 hex digest of [s], falling back to `hashCode` if SHA-1 is unavailable. */
    private fun hash(s: String): String = try {
        val md = MessageDigest.getInstance("SHA-1")
        HexFormat.of().formatHex(md.digest(s.toByteArray(StandardCharsets.UTF_8)))
    } catch (_: NoSuchAlgorithmException) {
        Integer.toHexString(s.hashCode())
    }

    /** A decoded thumbnail: the full-res GPU image, its average color, and a tiny blurred copy for the ambient backdrop. */
    private class Decoded(val image: NativeImage, val avgColor: Int, val ambientImage: NativeImage)

    /**
     * Decodes [bytes] into a [NativeImage] and registers it under [key] (the composite videoId +
     * quality key, or a direct-URL request's own key). The average color and ambient-backdrop
     * texture are stored per [avgColorKey] so a YouTube video's LOW/HIGH tiers share the one pair.
     */
    private fun register(avgColorKey: String, key: String, bytes: ByteArray) {
        var image: NativeImage? = null
        var tex: DynamicTexture? = null
        var ambientImage: NativeImage? = null
        var ambientTex: DynamicTexture? = null

        runCatching {
            val decoded = decode(bytes)
            image = decoded.image
            ambientImage = decoded.ambientImage
            //? if >=1.21.11 {
            tex = DynamicTexture({ "yt-thumb-$key" }, image)
            //?} else
            /*tex = DynamicTexture(image)*/
            val id = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "yt_thumb/${hash(key)}")
            Minecraft.getInstance().textureManager.register(id, tex)
            TextureUploadUtil.applyBilinearFilter(tex)
            AVG_COLOR.put(avgColorKey, decoded.avgColor)
            READY.put(key, id)

            if (AMBIENT_TEX.getIfPresent(avgColorKey) == null) {
                //? if >=1.21.11 {
                ambientTex = DynamicTexture({ "yt-thumb-ambient-$avgColorKey" }, ambientImage)
                //?} else
                /*ambientTex = DynamicTexture(ambientImage)*/
                val ambientId =
                    Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "yt_thumb_ambient/${hash(avgColorKey)}")
                Minecraft.getInstance().textureManager.register(ambientId, ambientTex)
                TextureUploadUtil.applyBilinearFilter(ambientTex)
                AMBIENT_TEX.put(avgColorKey, ambientId)
            } else {
                ambientImage.close()
            }
        }.onFailure { e ->
            // Runs inside a Minecraft.execute task, so nothing may escape onto the main thread
            logger.warn("Decode / register failed for $key: ${e.message}")
            BYTES.invalidate(key)
            // The texture manager owns the image only once registration succeeds; closing the
            // texture also closes its image, so close the bare image only when no texture exists yet.
            runCatching { tex?.close() ?: image?.close() }
            runCatching { ambientTex?.close() ?: ambientImage?.close() }
        }.also {
            IN_FLIGHT.invalidate(key)
        }
    }

    /**
     * Decodes [bytes] as a JPEG / PNG image into a GPU-ready RGBA [NativeImage], its average color,
     * and a tiny blurred [AmbientGrid]-derived copy used as the ambient backdrop — the same technique
     * YouTube uses for its loading / ambient thumbnail glow, rather than a single flat color.
     */
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
            val abgr = AmbientGrid.argbToAbgr(argb)
            //? if >=1.21.11 {
            image.setPixelABGR(i % w, i / w, abgr)
            //?} else
            /*image.setPixelRGBA(i % w, i / w, abgr)*/
        }

        val ambientImage = AmbientGrid.toNativeImage(AmbientGrid.fromArgbPixels(pixels, w, h))

        val n = pixels.size.coerceAtLeast(1)
        val avg = (0xFF shl 24) or (((rSum / n).toInt()) shl 16) or (((gSum / n).toInt()) shl 8) or (bSum / n).toInt()
        Decoded(image, avg, ambientImage)
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
            if (response.code != 200) {
                logger.warn("Thumbnail fetch got HTTP ${response.code} for $url.")
                null
            } else {
                response.body
            }
        } catch (e: Exception) {
            logger.warn("Thumbnail fetch failed for $url.", e)
            null
        }
    }
}
