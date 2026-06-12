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

    private val readyBufferRef = AtomicReference<ByteBuffer?>(null)
    private val textureReady = AtomicBoolean(false)
    private val reusableFrameBuffers = ConcurrentLinkedQueue<ByteBuffer>()

    private var uploader: AsyncTextureUploader? = null
    private var rgbaUploadBuffer: ByteBuffer? = null

    private var uploadTotalNs = 0L
    private var uploadCount = 0

    /** Returns true once a frame is available for upload or has already been uploaded to the GPU texture. */
    fun textureFilled(): Boolean = textureReady.get() || readyBufferRef.get() != null

    /**
     * Uploads the ready frame to [texture] if one is available.
     * [actualW] / [actualH] must match [expectedW] / [expectedH] the pipe was started with.
     *
     * Warning: this is one of the most expensive operations in the pipeline. It's critical to call this as soon as
     * possible after [textureFilled] returns true, to minimize the chance of the reader thread overwriting the ready
     * buffer before upload.
     */
    fun updateFrame(texture: GpuTexture, actualW: Int, actualH: Int, expectedW: Int, expectedH: Int) {
        val buf = readyBufferRef.getAndSet(null) ?: return
        if (actualW != expectedW || actualH != expectedH || Minecraft.getInstance().window.isMinimized) {
            recycleFrameBuffer(buf)
            return
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
                uploadTotalNs += System.nanoTime() - start
                MediaPlayer.framesToGpu.incrementAndGet()
                if (++uploadCount >= 60) {
                    val avgMs = uploadTotalNs / 60 / 1_000_000.0
                    logger.info("$debugLabel Upload avg. ${String.format("%.3f", avgMs)} ms / frame")
                    uploadTotalNs = 0L; uploadCount = 0
                }
            }
        } finally {
            recycleFrameBuffer(buf)
        }
    }

    /** Discards the current ready frame. Call when stopping or seeking. */
    fun clear() {
        textureReady.set(false)
        readyBufferRef.getAndSet(null)?.let(::recycleFrameBuffer)
        reusableFrameBuffers.clear()
    }

    /**
     * Releases the PBO ring. Must be called from the render thread when this surface is permanently
     * discarded (i.e., the owning player is being stopped for good).
     */
    fun cleanup() {
        clear()
        uploader?.cleanup()
        uploader = null
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
        if (dropped !== frame) dropped?.let(::recycleFrameBuffer)
        return takeReusableFrameBuffer(nextSize) ?: allocateFrameBuffer(nextSize)
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
        if (reusableFrameBuffers.size < MAX_REUSABLE_FRAME_BUFFERS) {
            reusableFrameBuffers.offer(buffer)
        }
    }
}
