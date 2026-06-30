package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.media.sink.DecodedVideoFrame
import com.dreamdisplays.api.render.TextureHandle
import com.dreamdisplays.api.render.TextureUploader
//? if >=1.21.11 {
import com.mojang.blaze3d.opengl.GlStateManager
//?} else
/*import com.mojang.blaze3d.platform.GlStateManager*/
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * Async, low-stall texture uploader based on a ring buffer of `Pixel Buffer Objects` (`PBO`).
 *
 * Workflow:
 * 1. Data is copied from system memory to `PBO`.
 * 2. GPU asynchronously copies data from `PBO` to texture (`PBO` -> Texture).
 * 3. Using multiple PBOs (ring) avoids stalls: while the GPU reads from one buffer, the CPU can write to another.
 *
 * @param stateCache If true, cached `GlStateManager` methods are used for state optimization.
 */
class AsyncTextureUploader(private val stateCache: Boolean) : TextureUploader {
    /** [PBO_COUNT] buffer IDs that we rotate through. */
    private val pboIds: IntArray = IntArray(PBO_COUNT) { GL15.glGenBuffers() }

    /** Tracks allocated size per PBO so steady-state uploads do not reallocate every frame. */
    private val pboCapacities: IntArray = IntArray(PBO_COUNT)

    /** Index of the `PBO` to write into; advanced on each upload. */
    private var ringIndex: Int = 0

    /** Managed texture ID and size. */
    private var managedTexId: Int = -1

    /** Managed texture size (width). */
    private var managedTexW: Int = -1

    /** Managed texture size (height). */
    private var managedTexH: Int = -1

    /** Async support. */
    override val supportsAsync: Boolean = true

    /** Maximum texture size. This should not be changed. */
    override val maxTextureSize: Int = 8192

    /** Upload texture. */
    override fun upload(frame: DecodedVideoFrame): TextureHandle {
        if (managedTexId == -1) {
            managedTexId = GL11.glGenTextures()
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, managedTexId)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        }
        val w = frame.width;
        val h = frame.height
        val buf = ByteBuffer.wrap(frame.data)
        if (w != managedTexW || h != managedTexH) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, managedTexId)
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf,
            )
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            managedTexW = w; managedTexH = h
        } else {
            upload(managedTexId, buf, w, h, UploadPixelFormat.RGB24)
        }
        return TextureHandle(managedTexId)
    }

    /** Releases the texture. */
    override fun release(handle: TextureHandle) {
        if (handle.id == managedTexId && managedTexId != -1) {
            GL11.glDeleteTextures(managedTexId)
            managedTexId = -1; managedTexW = -1; managedTexH = -1
        }
    }

    /** Releases the texture and cleans up `PBO`s. */
    override fun close() {
        if (managedTexId != -1) {
            GL11.glDeleteTextures(managedTexId)
            managedTexId = -1
        }
        cleanup()
    }

    /**
     * Uploads one decoded frame from [src] into the next `PBO` in the ring, then schedules an async
     * copy from that PBO into [textureId] at mipmap level 0.
     *
     * [src] should be a direct `ByteBuffer` whose position points to the start of the pixel data.
     * Position and limit are restored on return.
     *
     * This call never blocks the GPU!
     */
    fun upload(textureId: Int, src: ByteBuffer, w: Int, h: Int, format: UploadPixelFormat = UploadPixelFormat.RGB24) {
        val size = w * h * format.bytesPerPixel
        if (size <= 0 || src.remaining() < size) return

        // Pick the next PBO and advance the ring. By the time we return to this PBO
        // in N frames (N = PBO_COUNT), the GPU is guaranteed to have finished reading from it.
        val slot = ringIndex
        val pbo = pboIds[ringIndex]
        ringIndex = (ringIndex + 1) % PBO_COUNT

        bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo)

        if (size > pboCapacities[slot]) {
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, size.toLong(), GL15.GL_STREAM_DRAW)
            pboCapacities[slot] = size
        }

        copyIntoMappedPbo(src, size)

        bindTexture(textureId)

        pixelStore(GL11.GL_UNPACK_ALIGNMENT, format.unpackAlignment)
        pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
        pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)

        texSubImage2DFromPbo(w, h, format.glFormat)

        // Restore default alignment (4 bytes) and unbind the buffer.
        pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
        bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0)
    }

    /** Releases `PBO` IDs. Must be called in the same OpenGL context where they were created. */
    fun cleanup() {
        for (id in pboIds) if (id != 0) GL15.glDeleteBuffers(id)
        pboCapacities.fill(0)
    }

    /** Binds a buffer to the specified target. */
    @Suppress("SameParameterValue")
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
    private fun copyIntoMappedPbo(src: ByteBuffer, size: Int) {
        val savedLimit = src.limit()
        val savedPos = src.position()
        val view = src.duplicate()
        view.limit(savedPos + size)
        view.position(savedPos)

        val mapped = GL30.glMapBufferRange(
            GL21.GL_PIXEL_UNPACK_BUFFER,
            0L,
            size.toLong(),
            GL30.GL_MAP_WRITE_BIT or GL30.GL_MAP_INVALIDATE_BUFFER_BIT or GL30.GL_MAP_UNSYNCHRONIZED_BIT,
        )
        if (mapped != null) {
            mapped.limit(size)
            if (view.isDirect) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(view), MemoryUtil.memAddress(mapped), size.toLong())
            } else {
                mapped.put(view)
            }
            GL30.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER)
        } else {
            GL15.glBufferSubData(GL21.GL_PIXEL_UNPACK_BUFFER, 0L, view)
        }

        src.limit(savedLimit)
        src.position(savedPos)
    }

    /** Texture sub-image 2D from a `PBO`. */
    private fun texSubImage2DFromPbo(w: Int, h: Int, glFormat: Int) {
        if (stateCache) {
            GlStateManager._texSubImage2D(
                GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, glFormat, GL11.GL_UNSIGNED_BYTE, 0L,
            )
        } else {
            GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, glFormat, GL11.GL_UNSIGNED_BYTE, 0L,
            )
        }
    }

    companion object {
        /** Number of `PBO`s in the ring. */
        private const val PBO_COUNT = 3
    }
}
