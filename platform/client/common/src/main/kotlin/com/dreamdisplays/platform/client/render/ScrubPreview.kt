package com.dreamdisplays.platform.client.render

//? if >=1.21.11 {
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplays.media.player.process.FFmpegBinary
import com.dreamdisplays.media.player.process.MediaProcess
import com.dreamdisplays.media.runtime.MediaHostGuard
import com.dreamdisplays.platform.client.render.ScrubPreview.EXTRACT_CONCURRENCY
import com.dreamdisplays.platform.client.render.ScrubPreview.FRAMES
import com.dreamdisplays.platform.client.render.ScrubPreview.SAMPLE_COUNT
import com.dreamdisplays.platform.client.render.ScrubPreview.generate
import com.dreamdisplays.util.DreamCoroutines
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.mojang.blaze3d.platform.NativeImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.IOException
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Generates and caches seek-bar scrub-preview thumbnails: a sparse set of downscaled frames sampled
 * across a VOD's duration, extracted via the bundled `FFmpeg` binary (one process per sample, `-ss`
 * seek + single-frame JPEG output). In-memory only (regenerated each session); a key's frames are
 * evicted together and their textures released when the key falls out of the cache.
 */
object ScrubPreview {
    private val logger = LoggerFactory.getLogger("DreamDisplays/ScrubPreview")

    /**
     * Fixed frame dimensions (16:9, letterboxed) every sample is encoded to. Vanilla `blit`'s
     * `(u, v, width, height, textureWidth, textureHeight)` overload samples `width x height` texels
     * 1:1 starting at `(u, v)` — it does not scale to fit, so a caller (e.g. [SeekBar]) that wants a
     * pixel-accurate, uncropped preview must draw at exactly this size, not an arbitrary box size.
     */
    const val FRAME_WIDTH = 256
    const val FRAME_HEIGHT = 144

    /** Target number of sampled frames across the full duration. */
    private const val SAMPLE_COUNT = 20

    /** Never sample closer together than this, so short videos don't spawn a process per second. */
    private const val MIN_SAMPLE_SPACING_NANOS = 5_000_000_000L

    /** Max simultaneous `FFmpeg` extractions — each opens its own connection to the source, so this
     *  is capped well below [SAMPLE_COUNT] to avoid hammering the host while still finishing well
     *  under a minute instead of running fully sequentially. */
    private const val EXTRACT_CONCURRENCY = 8

    private class Frame(val timestampNanos: Long, val texture: Identifier)

    /**
     * Sorted (ascending timestamp) frames per video key, once generation has produced at least one.
     * Generation republishes this key's value incrementally as each new frame lands (a growing list
     * built from the same [Frame] instances each time), so only a true eviction (size / expiry / explicit
     * invalidate) should release textures — a same-key [RemovalCause.REPLACED] must not, or every new
     * frame would tear down the texture of every frame published before it.
     */
    private val FRAMES: Cache<String, List<Frame>> = Caffeine.newBuilder()
        .maximumSize(8)
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .removalListener<String, List<Frame>> { _, frames, cause ->
            if (cause != RemovalCause.REPLACED) releaseAll(frames)
        }
        .build()

    /** Tracks which keys are currently generating, so repeated hover-triggered [request] calls no-op. */
    private val IN_FLIGHT: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(64)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build()

    /**
     * Schedules background generation of scrub-preview frames for [key] (typically the video URL) if
     * not already generated or in flight. [rawUrl] is the current video's un-resolved stream URL (as
     * returned by `MediaPlayer.capturedStreamRawUrl()` — cheap, no I / O) and [durationNanos] the known
     * total duration; both must be valid (non-live, resolved) before calling. Redirect resolution and
     * host-safety checks run in the background, never on the calling (render) thread.
     */
    fun request(key: String, rawUrl: String, durationNanos: Long) {
        if (durationNanos <= 0) return
        if (FRAMES.getIfPresent(key) != null) return
        if (IN_FLIGHT.asMap().putIfAbsent(key, true) != null) return
        logger.info("Requesting scrub preview for $key (durationNanos=$durationNanos)")
        DreamCoroutines.clientIo.launch {
            try {
                val safeUrl = MediaHostGuard.resolveSafeUrl(rawUrl)
                generate(key, safeUrl, durationNanos)
            } catch (e: Exception) {
                logger.warn("Scrub preview generation failed for $key: ${e.message}", e)
                // Cache the failure (empty list) so a hover-triggered retry every frame doesn't
                // hammer the source with another full generation round; a video swap gets a fresh key.
                FRAMES.put(key, emptyList())
            } finally {
                IN_FLIGHT.invalidate(key)
            }
        }
    }

    /** Returns the texture of the frame nearest [positionNanos] for [key], or null if not ready yet. */
    fun frameAt(key: String, positionNanos: Long): Identifier? {
        val frames = FRAMES.getIfPresent(key) ?: return null
        if (frames.isEmpty()) return null
        var lo = 0
        var hi = frames.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (frames[mid].timestampNanos <= positionNanos) lo = mid else hi = mid - 1
        }
        return frames[lo].texture
    }

    /**
     * Extracts sample frames via `FFmpeg`, up to [EXTRACT_CONCURRENCY] at a time, publishing to
     * [FRAMES] after every completed frame — so [frameAt] can return whatever's ready long before
     * the full sample set finishes (a full sequential pass across ~20 samples can take well over a
     * minute; a hovering user shouldn't wait for the very last one).
     */
    private suspend fun generate(key: String, sourceUrl: String, durationNanos: Long) {
        val ffmpeg = FFmpegBinary.getPath()
        if (ffmpeg == null) {
            logger.warn("Scrub preview aborted for $key: no FFmpeg binary available")
            FRAMES.put(key, emptyList())
            return
        }
        val spacing = (durationNanos / SAMPLE_COUNT).coerceAtLeast(MIN_SAMPLE_SPACING_NANOS)
        val timestamps = generateSequence(spacing / 2) { it + spacing }.takeWhile { it < durationNanos }.toList()
        logger.info("Generating $key: ${timestamps.size} sample(s), concurrency=$EXTRACT_CONCURRENCY, ffmpeg=$ffmpeg")

        val collected = java.util.Collections.synchronizedList(ArrayList<Frame>(timestamps.size))
        val semaphore = Semaphore(EXTRACT_CONCURRENCY)
        FRAMES.put(key, emptyList())
        val outcomes = coroutineScope {
            timestamps.map { ts ->
                async {
                    semaphore.withPermit {
                        val bytes = extractFrame(key, ffmpeg, sourceUrl, ts)
                        val id = bytes?.let { registerFrame(key, ts, it) }
                        if (id != null) {
                            collected.add(Frame(ts, id))
                            FRAMES.put(key, collected.sortedBy { it.timestampNanos })
                        }
                        id != null
                    }
                }
            }.awaitAll()
        }
        val failures = outcomes.count { !it }
        logger.info("Generated $key: ${collected.size} frame(s) ready, $failures extraction failure(s)")
    }

    /** Runs a single-frame `FFmpeg` extraction at [offsetNanos] and returns the raw JPEG bytes. */
    private fun extractFrame(key: String, ffmpeg: String, sourceUrl: String, offsetNanos: Long): ByteArray? {
        val proc = try {
            MediaProcess.buildFrameExtract(ffmpeg, sourceUrl, offsetNanos, FRAME_WIDTH, FRAME_HEIGHT)
        } catch (e: IOException) {
            logger.warn("Scrub frame process start failed for $key@$offsetNanos: ${e.message}")
            return null
        }
        return try {
            // Both pipes must be drained concurrently: FFmpeg can block writing stderr (e.g. a
            // verbose error) while stdout is only read after waitFor, deadlocking the process.
            val stderrThread = Thread({ runCatching { proc.errorStream.readBytes() } }, "scrub-preview-stderr")
                .apply { isDaemon = true; start() }
            val bytes = proc.inputStream.use { it.readBytes() }
            val exited = proc.waitFor(10, TimeUnit.SECONDS)
            stderrThread.join(1_000)
            if (!exited) {
                logger.warn("Scrub frame extraction timed out for $key@$offsetNanos")
                null
            } else if (bytes.isEmpty()) {
                logger.warn("Scrub frame extraction produced no output for $key@$offsetNanos (exit=${proc.exitValue()})")
                null
            } else {
                bytes
            }
        } catch (e: Exception) {
            logger.warn("Scrub frame extraction failed for $key@$offsetNanos: ${e.message}")
            null
        } finally {
            MediaProcess.gracefulDestroy(proc)
        }
    }

    /** Decodes [bytes] and registers them as a Minecraft texture on the render thread; blocks the calling
     *  (background) thread until registration completes so [generate] can build an ordered frame list. */
    private fun registerFrame(key: String, timestampNanos: Long, bytes: ByteArray): Identifier? {
        val image = try {
            decode(bytes)
        } catch (e: Exception) {
            logger.warn("Scrub frame decode failed for $key@$timestampNanos: ${e.message}")
            return null
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Identifier? = null
        Minecraft.getInstance().execute {
            try {
                val texKey = "$key@$timestampNanos"
                //? if >=1.21.11 {
                val tex = DynamicTexture({ "scrub-$texKey" }, image)
                //?} else
                /*val tex = DynamicTexture(image)*/
                val id = Identifier.fromNamespaceAndPath("dreamdisplays", "scrub/${hash(texKey)}")
                Minecraft.getInstance().textureManager.register(id, tex)
                TextureUploadUtil.applyBilinearFilter(tex)
                result = id
            } catch (e: Exception) {
                logger.warn("Scrub frame register failed for $key@$timestampNanos: ${e.message}")
                runCatching { image.close() }
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    /** Decodes [bytes] (a JPEG) into a GPU-ready RGBA [NativeImage]. */
    @Throws(IOException::class)
    private fun decode(bytes: ByteArray): NativeImage = ByteArrayInputStream(bytes).use { input ->
        val src = ImageIO.read(input) ?: throw IOException("Unsupported scrub frame image (size=${bytes.size}).")
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
        }
        image
    }

    /** Unregisters and closes every frame's texture; called when a key is evicted from [FRAMES]. */
    private fun releaseAll(frames: List<Frame>?) {
        if (frames.isNullOrEmpty()) return
        Minecraft.getInstance().execute {
            for (f in frames) runCatching { Minecraft.getInstance().textureManager.release(f.texture) }
        }
    }

    /** Returns a SHA-1 hex digest of [s], falling back to `hashCode` if SHA-1 is unavailable. */
    private fun hash(s: String): String = try {
        val md = MessageDigest.getInstance("SHA-1")
        HexFormat.of().formatHex(md.digest(s.toByteArray(StandardCharsets.UTF_8)))
    } catch (_: NoSuchAlgorithmException) {
        Integer.toHexString(s.hashCode())
    }
}
