package com.dreamdisplays.platform.client.render

//? if >=1.21.11 {
//?} else
/*import com.mojang.blaze3d.platform.GlStateManager*/
import com.dreamdisplays.api.media.sink.DecodedVideoFrame
import com.dreamdisplays.api.render.TextureHandle
import com.dreamdisplays.api.render.TextureUploader
import com.dreamdisplays.platform.client.render.AsyncTextureUploader.Companion.PBO_COUNT
import com.mojang.blaze3d.opengl.GlStateManager
import org.lwjgl.opengl.*
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
 * Each ring slot is guarded by a fence sync ([GL32.glFenceSync]) placed right after the PBO -> texture
 * copy is issued; before the CPU writes into a slot again the fence is checked, so a slot is never
 * overwritten while the GPU may still be reading from it (no reliance on ring depth alone).
 *
 * When `GL_ARB_buffer_storage` (core GL 4.4) is available, slots use immutable persistently-mapped
 * storage: the buffer is mapped exactly once and frames are memcpy'd straight into the coherent
 * mapping, skipping the per-frame `glMapBufferRange`/`glUnmapBuffer` driver round-trip.
 *
 * On contexts without it (e.g. macOS GL 4.1) the classic map-with-`GL_MAP_UNSYNCHRONIZED_BIT` path is used,
 * which the fences also make safe.
 *
 * @param stateCache If true, cached `GlStateManager` methods are used for state optimization.
 */
class AsyncTextureUploader(private val stateCache: Boolean) : TextureUploader {
    /** One ring entry: a PBO with its allocated size, guarding fence and optional persistent mapping. */
    private class Slot {
        /** GL buffer id (0 until created). */
        var pbo: Int = 0

        /** Allocated size in bytes so steady-state uploads do not reallocate every frame. */
        var capacity: Int = 0

        /** Fence placed after the last PBO->texture copy from this slot (0 when none pending). */
        var fence: Long = 0L

        /** Persistent coherent mapping of the whole buffer, when buffer-storage is supported. */
        var persistent: ByteBuffer? = null
    }

    /** [PBO_COUNT] ring slots rotated through on each upload. */
    private val slots: Array<Slot> = Array(PBO_COUNT) { Slot().apply { pbo = GL15.glGenBuffers() } }

    /** Index of the slot to write into; advanced on each upload. */
    private var ringIndex: Int = 0

    /** Managed texture ID and size. */
    private var managedTexId: Int = -1

    /** Managed texture size (width). */
    private var managedTexW: Int = -1

    /** Managed texture size (height). */
    private var managedTexH: Int = -1

    /** True when immutable persistently-mapped buffer storage is available on this context. */
    private val persistentMapSupported: Boolean by lazy {
        runCatching {
            val caps = GL.getCapabilities()
            caps.OpenGL44 || caps.GL_ARB_buffer_storage
        }.getOrDefault(false)
    }

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

        // Pick the next slot and advance the ring
        val slot = slots[ringIndex]
        ringIndex = (ringIndex + 1) % PBO_COUNT

        // Make sure the GPU is done reading this slot's PBO before we overwrite it
        waitSlotFence(slot)

        bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, slot.pbo)
        ensureCapacity(slot, size)

        val persistent = slot.persistent
        if (persistent != null) {
            copyIntoPersistentPbo(src, persistent, size)
        } else {
            copyIntoMappedPbo(src, size)
        }

        bindTexture(textureId)

        pixelStore(GL11.GL_UNPACK_ALIGNMENT, format.unpackAlignment)
        pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
        pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)

        texSubImage2DFromPbo(w, h, format.glFormat, 0L)

        // Guard this slot until the GPU has consumed the copy we just issued
        slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)

        // Restore default alignment (4 bytes) and unbind the buffer.
        pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
        bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0)
    }

    /**
     * Uploads one packed I420 frame (Y plane, then U, then V) from [src] into three plane textures
     * in a single PBO pass: one ring slot, one fence wait, one memcpy of the whole frame, then three
     * `texSubImage2D` calls sourcing from plane offsets inside the same PBO. Compared to three
     * independent [upload] calls this pays the fixed per-call cost (bind / map / fence) once.
     */
    fun uploadPlanar(
        yId: Int, yW: Int, yH: Int,
        uId: Int, uW: Int, uH: Int,
        vId: Int, vW: Int, vH: Int,
        src: ByteBuffer,
    ) {
        val ySize = yW * yH
        val uSize = uW * uH
        val vSize = vW * vH
        val total = ySize + uSize + vSize
        if (total <= 0 || src.remaining() < total) return

        val slot = slots[ringIndex]
        ringIndex = (ringIndex + 1) % PBO_COUNT

        waitSlotFence(slot)

        bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, slot.pbo)
        ensureCapacity(slot, total)

        val persistent = slot.persistent
        if (persistent != null) {
            copyIntoPersistentPbo(src, persistent, total)
        } else {
            copyIntoMappedPbo(src, total)
        }

        pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1)
        pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
        pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)

        val glFormat = UploadPixelFormat.R8.glFormat
        bindTexture(yId)
        texSubImage2DFromPbo(yW, yH, glFormat, 0L)
        bindTexture(uId)
        texSubImage2DFromPbo(uW, uH, glFormat, ySize.toLong())
        bindTexture(vId)
        texSubImage2DFromPbo(vW, vH, glFormat, (ySize + uSize).toLong())

        // Guard this slot until the GPU has consumed all three plane copies
        slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)

        pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
        bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0)
    }

    /** Releases `PBO` IDs. Must be called in the same OpenGL context where they were created. */
    fun cleanup() {
        for (slot in slots) {
            if (slot.fence != 0L) {
                GL32.glDeleteSync(slot.fence)
                slot.fence = 0L
            }
            if (slot.persistent != null) {
                bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, slot.pbo)
                GL30.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER)
                bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0)
                slot.persistent = null
            }
            if (slot.pbo != 0) GL15.glDeleteBuffers(slot.pbo)
            slot.pbo = 0
            slot.capacity = 0
        }
    }

    /**
     * Blocks (briefly) until the GPU has finished the last copy issued from [slot]'s PBO.
     * With [PBO_COUNT] frames of latency the fence is almost always already signaled, making this
     * a zero-cost check in the steady state; the wait only kicks in when the GPU genuinely lags.
     */
    private fun waitSlotFence(slot: Slot) {
        val fence = slot.fence
        if (fence == 0L) return
        // Cheap non-blocking poll first; only flush + wait if the GPU is actually behind
        var status = GL32.glClientWaitSync(fence, 0, 0L)
        if (status == GL32.GL_TIMEOUT_EXPIRED) {
            status = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_TIMEOUT_NS)
        }
        GL32.glDeleteSync(fence)
        slot.fence = 0L
    }

    /**
     * (Re)allocates the currently bound PBO so it can hold [size] bytes. Persistent storage is
     * immutable, so growing a persistently-mapped slot recreates the buffer object.
     */
    private fun ensureCapacity(slot: Slot, size: Int) {
        if (size <= slot.capacity) return

        if (persistentMapSupported) {
            if (slot.persistent != null) {
                // Immutable storage can't be resized: unmap and replace the buffer object
                GL30.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER)
                slot.persistent = null
                bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0)
                GL15.glDeleteBuffers(slot.pbo)
                slot.pbo = GL15.glGenBuffers()
                bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, slot.pbo)
            }
            val flags = GL44.GL_MAP_WRITE_BIT or GL44.GL_MAP_PERSISTENT_BIT or GL44.GL_MAP_COHERENT_BIT
            if (GL.getCapabilities().OpenGL44) {
                GL44.glBufferStorage(GL21.GL_PIXEL_UNPACK_BUFFER, size.toLong(), flags)
            } else {
                ARBBufferStorage.glBufferStorage(GL21.GL_PIXEL_UNPACK_BUFFER, size.toLong(), flags)
            }
            slot.persistent = GL30.glMapBufferRange(GL21.GL_PIXEL_UNPACK_BUFFER, 0L, size.toLong(), flags)
            // If mapping unexpectedly failed, fall back to plain mutable storage for this slot
            if (slot.persistent == null) {
                GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, size.toLong(), GL15.GL_STREAM_DRAW)
            }
        } else {
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, size.toLong(), GL15.GL_STREAM_DRAW)
        }
        slot.capacity = size
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

    /** Copies [size] bytes from [src] straight into the slot's persistent coherent mapping. */
    private fun copyIntoPersistentPbo(src: ByteBuffer, dst: ByteBuffer, size: Int) {
        if (src.isDirect) {
            MemoryUtil.memCopy(
                MemoryUtil.memAddress(src, src.position()),
                MemoryUtil.memAddress0(dst),
                size.toLong(),
            )
        } else {
            val savedLimit = src.limit()
            val savedPos = src.position()
            val view = src.duplicate()
            view.limit(savedPos + size)
            val out = dst.duplicate()
            out.clear()
            out.put(view)
            src.limit(savedLimit)
            src.position(savedPos)
        }
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

    /** Texture sub-image 2D sourcing from the bound `PBO` at byte [offset]. */
    private fun texSubImage2DFromPbo(w: Int, h: Int, glFormat: Int, offset: Long) {
        if (stateCache) {
            GlStateManager._texSubImage2D(
                GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, glFormat, GL11.GL_UNSIGNED_BYTE, offset,
            )
        } else {
            GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, glFormat, GL11.GL_UNSIGNED_BYTE, offset,
            )
        }
    }

    companion object {
        /** Number of `PBO`s in the ring. */
        private const val PBO_COUNT = 3

        /** Upper bound on a fence wait when the GPU is genuinely behind (100 ms). */
        private const val FENCE_TIMEOUT_NS = 100_000_000L
    }
}
