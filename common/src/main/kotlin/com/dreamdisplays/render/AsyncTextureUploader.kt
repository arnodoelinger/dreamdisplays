package com.dreamdisplays.render

import com.dreamdisplays.media.api.DecodedVideoFrame
import com.dreamdisplays.render.api.TextureHandle
import com.dreamdisplays.render.api.TextureUploader
import com.mojang.blaze3d.opengl.GlStateManager
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21
import java.nio.ByteBuffer

/**
 * Async, zero-stall RGB texture uploader based on a ring buffer of `Pixel Buffer Objects` (`PBO`).
 *
 * Workflow:
 * 1. Data is copied from system memory to `PBO`.
 * 2. GPU asynchronously copies data from `PBO` to texture (`PBO` -> Texture).
 * 3. Using multiple PBOs (ring) avoids stalls: while the GPU reads from one buffer, the CPU can write to another.
 *
 * @param stateCache If true, cached `GlStateManager` methods are used for state optimization.
 */
class AsyncTextureUploader(private val stateCache: Boolean) : TextureUploader {

    /** [PBO_COUNT] persistent buffer IDs that we rotate through. */
    private val pboIds: IntArray = IntArray(PBO_COUNT) { GL15.glGenBuffers() }

    /** Tracks the largest allocated size so that reallocating the same size is efficient. */
    private var pboCapacity: Int = 0

    /** Index of the `PBO` to write into; advanced on each upload. */
    private var ringIndex: Int = 0

    private var managedTexId: Int = -1
    private var managedTexW: Int = -1
    private var managedTexH: Int = -1

    override val supportsAsync: Boolean = true
    override val maxTextureSize: Int = 8192

    override fun upload(frame: DecodedVideoFrame): TextureHandle {
        if (managedTexId == -1) {
            managedTexId = GL11.glGenTextures()
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, managedTexId)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        }
        val w = frame.width; val h = frame.height
        val buf = ByteBuffer.wrap(frame.data)
        if (w != managedTexW || h != managedTexH) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, managedTexId)
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf)
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            managedTexW = w; managedTexH = h
        } else {
            upload(managedTexId, buf, w, h)
        }
        return TextureHandle(managedTexId)
    }

    override fun release(handle: TextureHandle) {
        if (handle.id == managedTexId && managedTexId != -1) {
            GL11.glDeleteTextures(managedTexId)
            managedTexId = -1; managedTexW = -1; managedTexH = -1
        }
    }

    override fun close() {
        if (managedTexId != -1) {
            GL11.glDeleteTextures(managedTexId)
            managedTexId = -1
        }
        cleanup()
    }

    /**
     * Uploads `w * h * 3` bytes from [src] into the next `PBO` in the ring, then schedules an async
     * copy from that PBO into [textureId] at mipmap level 0 as `GL_RGB` / `GL_UNSIGNED_BYTE`.
     *
     * [src] must be a direct `ByteBuffer` whose position points to the start of the pixel data.
     * Position and limit are restored on return.
     *
     * This call never blocks the GPU!
     */
    fun upload(textureId: Int, src: ByteBuffer, w: Int, h: Int) {
        val size = w * h * 3
        if (size <= 0 || src.remaining() < size) return

        // Pick the next PBO and advance the ring. By the time we return to this PBO
        // in N frames (N = PBO_COUNT), the GPU is guaranteed to have finished reading from it.
        val pbo = pboIds[ringIndex]
        ringIndex = (ringIndex + 1) % PBO_COUNT

        bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo)

        // Orphaning: tell the driver that we no longer need the previous contents.
        // This allows the driver to allocate a new memory region for writing without waiting
        // for the GPU to finish with the old one. If the size matches, the driver can reuse memory efficiently.
        if (size > pboCapacity) {
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, size.toLong(), GL15.GL_STREAM_DRAW)
            pboCapacity = size
        } else {
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, pboCapacity.toLong(), GL15.GL_STREAM_DRAW)
        }

        // Copy pixels into the freshly allocated (orphaned) PBO
        val savedLimit = src.limit()
        val savedPos = src.position()
        src.limit(savedPos + size)
        GL15.glBufferSubData(GL21.GL_PIXEL_UNPACK_BUFFER, 0L, src)
        src.limit(savedLimit)
        src.position(savedPos)

        bindTexture(textureId)

        // GL_UNPACK_ALIGNMENT must be 1, 2, 4, or 8.
        // RGB rows have no natural alignment, so we set it to 1.
        pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1)
        pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
        pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)

        texSubImage2DFromPbo(w, h)

        // Restore default alignment (4 bytes) and unbind the buffer.
        pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
        bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0)
    }

    /** Releases `PBO` IDs. Must be called in the same OpenGL context where they were created. */
    fun cleanup() {
        for (id in pboIds) if (id != 0) GL15.glDeleteBuffers(id)
        pboCapacity = 0
    }

    /** Binds a buffer to the specified target. */
    private fun bindBuffer(target: Int, id: Int) {
        if (stateCache) GlStateManager._glBindBuffer(target, id) else GL15.glBindBuffer(target, id)
    }

    /** Binds a 2D texture. */
    private fun bindTexture(id: Int) {
        if (stateCache) GlStateManager._bindTexture(id) else GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
    }

    /** Sets pixel storage parameters. */
    private fun pixelStore(name: Int, value: Int) {
        if (stateCache) GlStateManager._pixelStore(name, value) else GL11.glPixelStorei(name, value)
    }

    /** 
     * Copies data from the currently bound `PBO` to the texture.
     * The offset parameter (0L) indicates that data is taken from the `PBO` buffer, not RAM.
     */
    private fun texSubImage2DFromPbo(w: Int, h: Int) {
        if (stateCache) {
            GlStateManager._texSubImage2D(
                GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, 0L,
            )
        } else {
            GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, 0L,
            )
        }
    }

    companion object {
        private const val PBO_COUNT = 3
    }
}
