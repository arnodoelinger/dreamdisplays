package com.dreamdisplays.player.pipeline

import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.render.AsyncTextureUploader
import com.dreamdisplays.render.TextureUploadUtil
import com.dreamdisplays.render.UploadPixelFormat
import com.mojang.blaze3d.textures.GpuTexture
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Render-facing half of a frame pipe, shared by [VideoFramePipe] and [NativeVideoFramePipe]:
 * the reusable direct-buffer pool, the ready-frame swap slot, and the GPU upload path.
 *
 * The reader thread fills a "spare" buffer and [publish]es it; the render thread consumes it
 * via [updateFrame] without ever blocking the reader.
 */
internal class FrameSurface(
    private val debugLabel: String,
    private val pixelFormat: UploadPixelFormat = UploadPixelFormat.RGB24,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/FrameSurface")

    companion object {
        private const val MAX_REUSABLE_FRAME_BUFFERS = 4
    }

    /** Pool retention cap; raised by the prebuffer so its in-flight buffers are reused, not churned. */
    @Volatile private var maxReusableBuffers = MAX_REUSABLE_FRAME_BUFFERS

    /** Raises the reusable-buffer pool size (used when a [FramePrebuffer] keeps many frames in flight). */
    fun setMaxReusableBuffers(n: Int) { maxReusableBuffers = n.coerceAtLeast(MAX_REUSABLE_FRAME_BUFFERS) }

    private val readyBufferRef = AtomicReference<ByteBuffer?>(null)
    private val textureReady = AtomicBoolean(false)
    private val readyDrops = AtomicLong()
    private val reusableFrameBuffers = ConcurrentLinkedQueue<ByteBuffer>()

    private var uploader: AsyncTextureUploader? = null
    private val planeUploaders = arrayOfNulls<AsyncTextureUploader>(3)
    private var rgbaUploadBuffer: ByteBuffer? = null

    private var uploadTotalNs = 0L
    private var uploadMinNs = Long.MAX_VALUE
    private var uploadMaxNs = 0L
    private var uploadCount = 0
    private var skippedUploads = 0L

    /** Returns true once a frame is available for upload or has already been uploaded to the GPU texture. */
    fun textureFilled(): Boolean = textureReady.get() || readyBufferRef.get() != null

    /**
     * Uploads the ready frame to [texture] if one is available.
     * [actualW] / [actualH] must match [expectedW] / [expectedH] the pipe was started with.
     *
     * Returns true when a frame was actually uploaded to [texture] (used by the dual-texture
     * quality handoff to detect when the new-resolution texture has received its first frame).
     *
     * Warning: this is one of the most expensive operations in the pipeline. It's critical to call this as soon as
     * possible after [textureFilled] returns true, to minimize the chance of the reader thread overwriting the ready
     * buffer before upload.
     */
    fun updateFrame(texture: GpuTexture, actualW: Int, actualH: Int, expectedW: Int, expectedH: Int): Boolean {
        val buf = readyBufferRef.getAndSet(null) ?: return false
        if (actualW != expectedW || actualH != expectedH || Minecraft.getInstance().window.isMinimized) {
            if (MediaPlayer.DEBUG) skippedUploads++
            recycleFrameBuffer(buf)
            return false
        }
        buf.rewind()
        val start = System.nanoTime()
        try {
            TextureUploadUtil.upload(
                texture = texture,
                src = buf,
                w = texture.getWidth(0),
                h = texture.getHeight(0),
                format = pixelFormat,
                glUploader = { uploader ?: AsyncTextureUploader(stateCache = true).also { uploader = it } },
                rgbaScratch = rgbaUploadBuffer,
                setRgbaScratch = { rgbaUploadBuffer = it },
            )
            textureReady.set(true)
            if (MediaPlayer.DEBUG) {
                recordUpload(System.nanoTime() - start, "Upload", texture.getWidth(0), texture.getHeight(0))
                MediaPlayer.framesToGpu.incrementAndGet()
            }
            return true
        } finally {
            recycleFrameBuffer(buf)
        }
    }

    /**
     * Uploads the ready I420 frame (Y, then U, then V planes) into the three plane textures.
     * Plane dimensions come from the textures themselves; [actualW] / [actualH] must match
     * [expectedW] / [expectedH] like in [updateFrame]. Each plane gets its own PBO ring so
     * the three uploads of one frame never recycle each other's buffers.
     */
    fun updateFramePlanar(
        y: GpuTexture, u: GpuTexture, v: GpuTexture,
        actualW: Int, actualH: Int, expectedW: Int, expectedH: Int,
    ): Boolean {
        val buf = readyBufferRef.getAndSet(null) ?: return false
        if (actualW != expectedW || actualH != expectedH || Minecraft.getInstance().window.isMinimized) {
            if (MediaPlayer.DEBUG) skippedUploads++
            recycleFrameBuffer(buf)
            return false
        }
        val start = System.nanoTime()
        try {
            var offset = 0
            for ((i, texture) in arrayOf(y, u, v).withIndex()) {
                val planeBytes = texture.getWidth(0) * texture.getHeight(0)
                val view = buf.duplicate()
                view.position(offset).limit(offset + planeBytes)
                TextureUploadUtil.upload(
                    texture = texture,
                    src = view,
                    w = texture.getWidth(0),
                    h = texture.getHeight(0),
                    format = UploadPixelFormat.R8,
                    glUploader = { planeUploader(i) },
                    rgbaScratch = null,
                    setRgbaScratch = {},
                )
                offset += planeBytes
            }
            textureReady.set(true)
            if (MediaPlayer.DEBUG) {
                recordUpload(System.nanoTime() - start, "Planar upload", y.getWidth(0), y.getHeight(0))
                MediaPlayer.framesToGpu.incrementAndGet()
            }
            return true
        } finally {
            recycleFrameBuffer(buf)
        }
    }

    /** Lazily creates the per-plane GL uploader for plane [i] (0 = Y, 1 = U, 2 = V). */
    private fun planeUploader(i: Int): AsyncTextureUploader =
        planeUploaders[i] ?: AsyncTextureUploader(stateCache = true).also { planeUploaders[i] = it }

    /** Discards the current ready frame. Call when stopping or seeking. */
    fun clear() {
        textureReady.set(false)
        readyBufferRef.getAndSet(null)?.let(::recycleFrameBuffer)
        reusableFrameBuffers.clear()
        readyDrops.set(0)
    }

    /**
     * Releases the PBO ring. Must be called from the render thread when this surface is permanently
     * discarded (i.e., the owning player is being stopped for good).
     */
    fun cleanup() {
        clear()
        uploader?.cleanup()
        uploader = null
        for (i in planeUploaders.indices) {
            planeUploaders[i]?.cleanup()
            planeUploaders[i] = null
        }
        rgbaUploadBuffer = null
    }

    /** Drops the ready frame and the buffer pool without recycling (used when the frame size changes). */
    fun resetPool() {
        readyBufferRef.set(null)
        reusableFrameBuffers.clear()
    }

    /**
     * Swaps [frame] into the ready slot for the render thread and returns a fresh spare buffer
     * of at least [nextSize] bytes for the reader to fill next.
     */
    fun publish(frame: ByteBuffer, nextSize: Int): ByteBuffer {
        val dropped = readyBufferRef.getAndSet(frame)
        if (dropped !== frame) dropped?.let {
            readyDrops.incrementAndGet()
            if (MediaPlayer.DEBUG) MediaPlayer.framesDropped.incrementAndGet()
            recycleFrameBuffer(it)
        }
        return takeReusableFrameBuffer(nextSize) ?: allocateFrameBuffer(nextSize)
    }

    /**
     * Consumer-side present (used by [FramePrebuffer]): swaps [frame] into the ready slot, recycling any
     * frame the render thread hadn't picked up yet. Unlike [publish] it returns no spare — the prebuffer's
     * producer owns spare allocation.
     */
    fun present(frame: ByteBuffer) {
        val dropped = readyBufferRef.getAndSet(frame)
        if (dropped !== frame) dropped?.let {
            readyDrops.incrementAndGet()
            if (MediaPlayer.DEBUG) MediaPlayer.framesDropped.incrementAndGet()
            recycleFrameBuffer(it)
        }
    }

    /** Returns a pooled or freshly allocated direct buffer of at least [size] bytes. */
    fun takeOrAllocate(size: Int): ByteBuffer = takeReusableFrameBuffer(size) ?: allocateFrameBuffer(size)

    /** Allocate a new direct `ByteBuffer` of at least [size] bytes. The caller is responsible for recycling it when done. */
    fun allocateFrameBuffer(size: Int): ByteBuffer =
        ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())

    /**
     * Takes and returns a reusable frame buffer of at least [requiredSize] bytes, or null if none are available.
     * The caller is responsible for clearing and recycling the returned buffer when done.
     */
    fun takeReusableFrameBuffer(requiredSize: Int): ByteBuffer? {
        while (true) {
            val buffer = reusableFrameBuffers.poll() ?: return null
            if (buffer.capacity() >= requiredSize) {
                buffer.clear()
                return buffer
            }
        }
    }

    /**
     * Recycles [buffer] for future reuse. The buffer will be cleared before reuse, but the caller must ensure it's not
     * currently in use (e.g., by the render thread).
     */
    fun recycleFrameBuffer(buffer: ByteBuffer) {
        buffer.clear()
        if (reusableFrameBuffers.size < maxReusableBuffers) {
            reusableFrameBuffers.offer(buffer)
        }
    }

    private fun recordUpload(elapsedNs: Long, label: String, w: Int, h: Int) {
        uploadTotalNs += elapsedNs
        uploadMinNs = minOf(uploadMinNs, elapsedNs)
        uploadMaxNs = maxOf(uploadMaxNs, elapsedNs)
        if (++uploadCount >= 60) {
            val avgMs = uploadTotalNs / uploadCount / 1_000_000.0
            val minMs = uploadMinNs / 1_000_000.0
            val maxMs = uploadMaxNs / 1_000_000.0
            val drops = readyDrops.getAndSet(0)
            val skipped = skippedUploads
            logger.info(
                "$debugLabel $label ${w}x$h avg=${"%.3f".format(avgMs)}ms " +
                        "min=${"%.3f".format(minMs)}ms max=${"%.3f".format(maxMs)}ms " +
                        "readyDrops=$drops skipped=$skipped pool=${reusableFrameBuffers.size}",
            )
            uploadTotalNs = 0L
            uploadMinNs = Long.MAX_VALUE
            uploadMaxNs = 0L
            uploadCount = 0
            skippedUploads = 0L
        }
    }
}
