package com.dreamdisplays.platform.client.render

//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplays.media.player.process.FFmpegBinary
import com.dreamdisplays.media.player.process.MediaProcess
import com.dreamdisplays.api.security.policy.MediaHosts
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import com.dreamdisplays.platform.client.render.ScrubPreview.EXTRACT_CONCURRENCY
import com.dreamdisplays.platform.client.render.ScrubPreview.FRAMES
import com.dreamdisplays.platform.client.render.ScrubPreview.generate
import com.dreamdisplays.util.DreamCoroutines
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.mojang.blaze3d.platform.NativeImage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.IOException
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/** Generates and caches seek-bar scrub-preview thumbnails: sparse frames sampled across video duration via `FFmpeg`. */
object ScrubPreview {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Fixed frame dimensions (16:9, letterboxed) every sample is encoded to. Used by Vanilla blit rendering. */
    const val FRAME_WIDTH = 256
    const val FRAME_HEIGHT = 144

    /** Target number of sampled frames across the full duration. */
    private const val SAMPLE_COUNT = 20

    /**
     * Samples taken from a host nobody vouched for. Each sample is its own connection and its own
     * range read, and every client that can see the display makes the same set — a full sweep of
     * one pasted address by a room full of players is a burst that host never asked for. A coarser
     * preview is a fair trade for not being a hammer.
     */
    private const val THIRD_PARTY_SAMPLE_COUNT = 6

    /** Never sample closer together than this, so short videos don't spawn a process per second. */
    private const val MIN_SAMPLE_SPACING_NANOS = 5_000_000_000L

    /** Max simultaneous FFmpeg extractions: each opens its own connection, so concurrency is capped conservatively. */
    private const val EXTRACT_CONCURRENCY = 2

    /** Extractions run one at a time against a pasted host (see [THIRD_PARTY_SAMPLE_COUNT]). */
    private const val THIRD_PARTY_CONCURRENCY = 1

    /** Budget for one sample: extraction requires deep range-seek into file with unknown codec/container overhead. */
    private val EXTRACT_TIMEOUT = 25.seconds

    private class Frame(val timestampNanos: Long, val texture: Identifier)

    /** Sorted (ascending timestamp) frames per video key; generation retries until all samples are extracted. */
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

    /** Schedules background generation of scrub-preview frames for [key] if not already generated or in flight. */
    fun request(key: String, rawUrl: String, durationNanos: Long, seekByDecoding: Boolean = false) {
        if (durationNanos <= 0 || FRAMES.getIfPresent(key) != null) return
        if (IN_FLIGHT.asMap().putIfAbsent(key, true) != null) return

        logger.info("Requesting scrub preview for $key (durationNanos=$durationNanos)")

        DreamCoroutines.clientIo.launch {
            runCatching {
                val safeUrl = MediaHostGuard.resolveSafeUrl(rawUrl)
                generate(key, safeUrl, durationNanos, seekByDecoding)
            }.onFailure { e ->
                if (e is CancellationException) throw e

                logger.warn("Scrub preview generation failed for $key: ${e.message}", e)
                FRAMES.put(key, emptyList())
            }.also {
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

    /** Extracts sample frames via `FFmpeg` at [EXTRACT_CONCURRENCY] limit, publishing to [FRAMES] as each completes. */
    private suspend fun generate(key: String, sourceUrl: String, durationNanos: Long, seekByDecoding: Boolean) {
        val ffmpeg = FFmpegBinary.getPath()
        if (ffmpeg == null) {
            logger.warn("Scrub preview aborted for $key: no FFmpeg binary available")
            FRAMES.put(key, emptyList())
            return
        }
        val firstParty = MediaHosts.isFirstParty(sourceUrl)
        val samples = if (firstParty) SAMPLE_COUNT else THIRD_PARTY_SAMPLE_COUNT
        val concurrency = if (firstParty) EXTRACT_CONCURRENCY else THIRD_PARTY_CONCURRENCY
        val spacing = (durationNanos / samples).coerceAtLeast(MIN_SAMPLE_SPACING_NANOS)
        val timestamps = generateSequence(spacing / 2) { it + spacing }.takeWhile { it < durationNanos }.toList()
        logger.info("Generating $key: ${timestamps.size} sample(s), concurrency=$concurrency, ffmpeg=$ffmpeg")

        val collected = Collections.synchronizedList(ArrayList<Frame>(timestamps.size))
        val semaphore = Semaphore(concurrency)
        FRAMES.put(key, emptyList())
        val outcomes = coroutineScope {
            timestamps.map { ts ->
                async {
                    semaphore.withPermit {
                        val bytes = extractFrame(key, ffmpeg, sourceUrl, ts, seekByDecoding)
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
    private suspend fun extractFrame(
        key: String, ffmpeg: String, sourceUrl: String, offsetNanos: Long, seekByDecoding: Boolean,
    ): ByteArray? =
        coroutineScope {
            val proc = runCatching {
                MediaProcess.buildFrameExtract(
                    ffmpeg, sourceUrl, offsetNanos, FRAME_WIDTH, FRAME_HEIGHT, seekByDecoding,
                )
            }.onFailure { e ->
                logger.warn("Scrub frame process start failed for $key@$offsetNanos: ${e.message}")
            }.getOrNull() ?: return@coroutineScope null

            val stderrDeferred =
                async(Dispatchers.IO) { runCatching { proc.errorStream.use { it.readBytes() } }.getOrNull() }
            val stdoutDeferred =
                async(Dispatchers.IO) { runCatching { proc.inputStream.use { it.readBytes() } }.getOrNull() }

            val result = runCatching {
                val exited = withTimeoutOrNull(EXTRACT_TIMEOUT) {
                    withContext(Dispatchers.IO) { proc.waitFor() }
                }

                val bytes = stdoutDeferred.await()
                stderrDeferred.await()

                when {
                    exited == null -> {
                        logger.warn("Scrub frame extraction timed out for $key@$offsetNanos.")
                        null
                    }

                    bytes == null || bytes.isEmpty() -> {
                        logger.warn("Scrub frame extraction produced no output for $key@$offsetNanos (exit=${proc.exitValue()}).")
                        null
                    }

                    else -> bytes
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                logger.warn("Scrub frame extraction failed for $key@$offsetNanos: ${e.message}.")
            }
            try {
                result.getOrNull()
            } finally {
                MediaProcess.gracefulDestroy(proc)
            }
        }

    /**
     * Decodes [bytes] and registers them as a Minecraft texture on the render thread; blocks the calling
     * (background) thread until registration completes so [generate] can build an ordered frame list.
     */
    private fun registerFrame(key: String, timestampNanos: Long, bytes: ByteArray): Identifier? {
        val image = runCatching {
            decode(bytes)
        }.onFailure { e ->
            logger.warn("Scrub frame decode failed for $key@$timestampNanos: ${e.message}.")
        }.getOrNull() ?: return null

        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Identifier? = null
        Minecraft.getInstance().execute {
            runCatching {
                val texKey = "$key@$timestampNanos"
                //? if >=1.21.11 {
                val tex = DynamicTexture({ "scrub-$texKey" }, image)
                //?} else
                /*val tex = DynamicTexture(image)*/
                val id = Identifier.fromNamespaceAndPath("dreamdisplays", "scrub/${hash(texKey)}")
                Minecraft.getInstance().textureManager.register(id, tex)
                TextureUploadUtil.applyBilinearFilter(tex)
                result = id
            }.onFailure { e ->
                logger.warn("Scrub frame register failed for $key@$timestampNanos: ${e.message}")
                runCatching { image.close() }
            }.also {
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
