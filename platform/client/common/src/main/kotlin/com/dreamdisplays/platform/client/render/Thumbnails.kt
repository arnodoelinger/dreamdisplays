package com.dreamdisplays.platform.client.render

//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplays.api.media.source.YouTubeUrls
import com.dreamdisplays.shared.policy.MediaHosts
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
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.coroutines.cancellation.CancellationException

/** Downloads, caches, and registers video thumbnails from YouTube and other sources as Minecraft textures. */
object Thumbnails {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val READY: Cache<String, Identifier> = Caffeine.newBuilder()
        .maximumSize(1_024)
        .expireAfterAccess(6, TimeUnit.HOURS)
        .removalListener<String, Identifier> { _, id, _ -> releaseTexture(id) }
        .build()

    private val AVG_COLOR: Cache<String, Int> = Caffeine.newBuilder()
        .maximumSize(1_024)
        .expireAfterAccess(6, TimeUnit.HOURS)
        .build()

    private val AMBIENT_TEX: Cache<String, Identifier> = Caffeine.newBuilder()
        .maximumSize(1_024)
        .expireAfterAccess(6, TimeUnit.HOURS)
        .removalListener<String, Identifier> { _, id, _ -> releaseTexture(id) }
        .build()

    private val IN_FLIGHT: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(512)
        .expireAfterWrite(2, TimeUnit.MINUTES)
        .build()

    private val FAILED: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(1_024)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build()

    private val BYTES = AsyncMemo<String, ByteArray>(
        maxSize = 512,
        ttlMs = 30L * 60L * 1_000L,
        scope = DreamCoroutines.clientIo,
        tag = "thumbnail",
    )

    private val THUMB_CACHE_DIR: Path = Path.of("config", "dreamdisplays", "thumb-cache")

    private const val THUMB_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1_000L

    private fun releaseTexture(id: Identifier?) {
        if (id == null) return
        Minecraft.getInstance().execute { Minecraft.getInstance().textureManager.release(id) }
    }

    private const val MAXRES_PLACEHOLDER_MAX_BYTES = 4_096

    private fun key(videoId: String, quality: Quality): String =
        if (quality == Quality.HIGH) videoId else "$videoId@lq"

    init {
        try {
            ImageIO.scanForPlugins()
        } catch (t: Throwable) {
            logger.warn("ImageIO.scanForPlugins failed: ${t.message}. This should never happen.")
        }
    }

    /** Requested thumbnail resolution tier: [HIGH] for the big preview, [LOW] for the small cards. */
    enum class Quality { HIGH, LOW }

    /** Returns the registered Minecraft texture [Identifier] for [videoId] at [quality], or null if not loaded. */
    fun get(videoId: String, quality: Quality = Quality.HIGH): Identifier? = READY.getIfPresent(key(videoId, quality))

    /** Returns the average ARGB color of [videoId]'s thumbnail, or null until it has been decoded. */
    fun averageColor(videoId: String): Int? = AVG_COLOR.getIfPresent(videoId)

    /** Returns the blurred ambient-backdrop texture [Identifier] for [videoId], or null until decoded. */
    fun ambientTexture(videoId: String): Identifier? = AMBIENT_TEX.getIfPresent(videoId)

    /** True when [videoId]'s thumbnail at [quality] recently failed, so the UI can fall back to placeholder art instead of retrying every frame. */
    fun isFailed(videoId: String, quality: Quality = Quality.HIGH): Boolean =
        FAILED.getIfPresent(key(videoId, quality)) != null

    /** Schedules a background download of [videoId]'s thumbnail at [quality] if not already in flight, ready, or recently failed. */
    fun request(videoId: String, quality: Quality = Quality.HIGH) {
        val k = key(videoId, quality)
        if (READY.getIfPresent(k) != null || FAILED.getIfPresent(k) != null) return
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
        if (READY.getIfPresent(key) != null || FAILED.getIfPresent(key) != null) return
        if (IN_FLIGHT.asMap().putIfAbsent(key, true) != null) return
        DreamCoroutines.clientIo.launch { download(key, key, loadBytesAsync(key) { fetch(directUrl) }) }
    }

    private fun loadBytesAsync(key: String, fetchBytes: () -> ByteArray?): Deferred<ByteArray> =
        BYTES.load(key) { loadBytes(key, fetchBytes) }

    private fun loadBytes(key: String, fetchBytes: () -> ByteArray?): ByteArray {
        readDiskCache(key)?.let { return it }
        val bytes = fetchBytes() ?: throw IOException("thumbnail HTTP fetch failed")
        writeDiskCacheAsync(key, bytes)
        return bytes
    }

    private fun fetchForQuality(videoId: String, quality: Quality): ByteArray? = when (quality) {
        Quality.LOW -> fetch(YouTubeUrls.mqThumbnailUrl(videoId))
        Quality.HIGH -> {
            val maxRes = fetch(YouTubeUrls.maxResThumbnailUrl(videoId))
            if (maxRes != null && maxRes.size > MAXRES_PLACEHOLDER_MAX_BYTES) maxRes
            else fetch(YouTubeUrls.mqThumbnailUrl(videoId))
        }
    }

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
                FAILED.put(cacheKey, true)
            }
    }

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

    private fun thumbFile(cacheKey: String): File =
        File(THUMB_CACHE_DIR.toFile(), hash(cacheKey) + ".jpg")

    private fun hash(s: String): String = try {
        val md = MessageDigest.getInstance("SHA-1")
        HexFormat.of().formatHex(md.digest(s.toByteArray(StandardCharsets.UTF_8)))
    } catch (_: NoSuchAlgorithmException) {
        Integer.toHexString(s.hashCode())
    }

    private class Decoded(val image: NativeImage, val avgColor: Int, val ambientImage: NativeImage)

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
            FAILED.put(key, true)
            // The texture manager owns the image only once registration succeeds; closing the
            // texture also closes its image, so close the bare image only when no texture exists yet.
            runCatching { tex?.close() ?: image?.close() }
            runCatching { ambientTex?.close() ?: ambientImage?.close() }
        }.also {
            IN_FLIGHT.invalidate(key)
        }
    }

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

    /**
     * Keeps thumbnail requests aligned with the supported-platform host policy: platform CDNs get
     * their own site's `Referer` when needed for hotlink protection, while custom hosts get none.
     */
    private fun refererFor(url: String): String? = MediaHosts.refererFor(url)

    private fun fetch(url: String): ByteArray? {
        return try {
            val response = DreamHttpClient.execute(
                url,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
                        "Accept" to "image/jpeg,image/png,image/webp",
                        *listOfNotNull(refererFor(url)?.let { "Referer" to it }).toTypedArray(),
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
