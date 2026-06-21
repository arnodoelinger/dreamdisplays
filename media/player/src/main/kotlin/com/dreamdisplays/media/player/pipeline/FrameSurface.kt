package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.core.media.FramePixelFormat
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.api.FrameUploader
import com.dreamdisplays.media.player.api.FrameUploaderFactory
import com.dreamdisplays.media.player.api.GpuTextureRef
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
 * via [updateFrame] without ever blocking the reader. The actual GPU upload is delegated to a
 * platform-supplied [FrameUploader] so this class stays free of any rendering API.
 */
internal class FrameSurface(
    private val debugLabel: String,
    uploaderFactory: FrameUploaderFactory,
    private val pixelFormat: FramePixelFormat = FramePixelFormat.RGB24,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/FrameSurface")

    /** Platform GPU upload sink for this channel; holds persistent upload state (e.g. a PBO ring). */
    private val uploader: FrameUploader = uploaderFactory.create()

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

    private var uploadTotalNs = 0L
    private var uploadMinNs = Long.MAX_VALUE
    private var uploadMaxNs = 0L
    private var uploadCount = 0
    private var skippedUploads = 0L

    /** Returns true once a frame is available for upload or has already been uploaded to the GPU texture. */
    fun textureFilled(): Boolean = textureReady.get() || readyBufferRef.get() != null

    /**
     * Uploads the ready frame to [target] if one is available.
     * [actualW] / [actualH] must match [expectedW] / [expectedH] the pipe was started with.
     *
     * Returns true when a frame was actually uploaded to [target] (used by the dual-texture
     * quality handoff to detect when the new-resolution texture has received its first frame).
     *
     * Warning: this is one of the most expensive operations in the pipeline. It's critical to call this as soon as
     * possible after [textureFilled] returns true, to minimize the chance of the reader thread overwriting the ready
     * buffer before upload.
     */
    fun updateFrame(target: GpuTextureRef, actualW: Int, actualH: Int, expectedW: Int, expectedH: Int): Boolean {
        val buf = readyBufferRef.getAndSet(null) ?: return false
        if (actualW != expectedW || actualH != expectedH || !uploader.canUpload()) {
            if (MediaPlayer.DEBUG) skippedUploads++
            recycleFrameBuffer(buf)
            return false
        }
        buf.rewind()
        val start = System.nanoTime()
        try {
            val uploaded = uploader.uploadInterleaved(target, buf, pixelFormat)
            if (uploaded) {
                textureReady.set(true)
                if (MediaPlayer.DEBUG) {
                    recordUpload(System.nanoTime() - start, "Upload", actualW, actualH)
                    MediaPlayer.framesToGpu.incrementAndGet()
                }
            }
            return uploaded
        } finally {
            recycleFrameBuffer(buf)
        }
    }

    /**
     * Uploads the ready I420 frame (Y, then U, then V planes) into the three plane textures.
     * [actualW] / [actualH] must match [expectedW] / [expectedH] like in [updateFrame].
     */
    fun updateFramePlanar(
        y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef,
        actualW: Int, actualH: Int, expectedW: Int, expectedH: Int,
    ): Boolean {
        val buf = readyBufferRef.getAndSet(null) ?: return false
        if (actualW != expectedW || actualH != expectedH || !uploader.canUpload()) {
            if (MediaPlayer.DEBUG) skippedUploads++
            recycleFrameBuffer(buf)
            return false
        }
        buf.rewind()
        val start = System.nanoTime()
        try {
            val uploaded = uploader.uploadPlanar(y, u, v, buf)
            if (uploaded) {
                textureReady.set(true)
                if (MediaPlayer.DEBUG) {
                    recordUpload(System.nanoTime() - start, "Planar upload", actualW, actualH)
                    MediaPlayer.framesToGpu.incrementAndGet()
                }
            }
            return uploaded
        } finally {
            recycleFrameBuffer(buf)
        }
    }

    /** Discards the current ready frame. Call when stopping or seeking. */
    fun clear() {
        textureReady.set(false)
        readyBufferRef.getAndSet(null)?.let(::recycleFrameBuffer)
        reusableFrameBuffers.clear()
        readyDrops.set(0)
    }

    /**
     * Releases the GPU upload resources. Must be called from the render thread when this surface is
     * permanently discarded (i.e., the owning player is being stopped for good).
     */
    fun cleanup() {
        clear()
        uploader.cleanup()
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
            logger.debug(
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
