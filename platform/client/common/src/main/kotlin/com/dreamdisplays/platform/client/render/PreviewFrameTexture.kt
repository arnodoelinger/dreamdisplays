package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.Initializer
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Holds the display menu's RGBA preview texture for one display, fed by [updateFrame] (decode thread)
 * and uploaded to the GPU by [uploadFrame] (render thread). Owned by `DisplayScreen` rather than the
 * menu screen itself, so the last decoded frame — and the GPU texture holding it — survive closing and
 * reopening the display menu: a paused (or otherwise frame-starved) video would otherwise show the
 * "waiting" placeholder on reopen instead of its frozen picture, since nothing re-pushes a frame until
 * playback resumes. Released only in `DisplayScreen.unregister()`.
 */
class PreviewFrameTexture(private val uuid: UUID) {
    @Volatile
    private var frontBuf: ByteBuffer = EMPTY_DIRECT

    private var backBuf: ByteBuffer = EMPTY_DIRECT

    @Volatile
    private var frameW = 0

    @Volatile
    private var frameH = 0

    @Volatile
    private var frameFormat = UploadPixelFormat.RGB24

    @Volatile
    private var frameVersion = 0L

    private var uploadedVersion = 0L

    private var dynamicTexture: DynamicTexture? = null
    var textureId: Identifier? = null
        private set
    var texW = 0
        private set
    var texH = 0
        private set
    private var uploader: AsyncTextureUploader? = null
    private var rgbaUploadBuffer: ByteBuffer? = null

    fun updateFrame(buf: ByteBuffer, w: Int, h: Int, format: UploadPixelFormat) {
        val size = w * h * format.bytesPerPixel
        if (size <= 0 || buf.remaining() < size) return
        var back = backBuf
        if (back.capacity() < size) {
            back = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
        back.clear()
        val savedLimit = buf.limit()
        val savedPos = buf.position()
        buf.limit(savedPos + size)
        back.put(buf)
        buf.limit(savedLimit)
        buf.position(savedPos)
        back.flip()

        val prev = frontBuf
        frontBuf = back
        backBuf =
            if (prev.capacity() >= size) prev else ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        frameW = w
        frameH = h
        frameFormat = format
        frameVersion++
    }

    fun uploadFrame() {
        val fw = frameW
        val fh = frameH
        val version = frameVersion
        if (version == uploadedVersion) return
        val buf = frontBuf
        val format = frameFormat
        val size = fw * fh * format.bytesPerPixel
        if (fw <= 0 || fh <= 0 || buf.remaining() < size) return

        val mc = Minecraft.getInstance()
        var tex = dynamicTexture
        if (tex == null || texW != fw || texH != fh) {
            tex?.close()
            textureId?.let { mc.textureManager.release(it) }
            val img = NativeImage(NativeImage.Format.RGBA, fw, fh, false)
            //? if >=1.21.11 {
            tex = DynamicTexture({ "dreamdisplays:preview" }, img)
            //?} else
            /*tex = DynamicTexture(img)*/
            textureId = Identifier.fromNamespaceAndPath(
                Initializer.MOD_ID,
                "preview/${uuid}-${UUID.randomUUID()}",
            )
            mc.textureManager.register(textureId!!, tex)
            TextureUploadUtil.applyBilinearFilter(tex)
            dynamicTexture = tex
            texW = fw
            texH = fh
        }

        TextureUploadUtil.uploadDynamicTexture(
            texture = tex,
            src = buf,
            w = fw,
            h = fh,
            format = format,
            glUploader = { uploader ?: AsyncTextureUploader(stateCache = true).also { uploader = it } },
            rgbaScratch = rgbaUploadBuffer,
            setRgbaScratch = { rgbaUploadBuffer = it },
        )
        uploadedVersion = version
    }

    /** Releases the GPU texture / uploader immediately. GL calls — render thread only. */
    fun close() {
        uploader?.close()
        uploader = null
        val mc = Minecraft.getInstance()
        dynamicTexture?.close()
        textureId?.let { mc.textureManager.release(it) }
        dynamicTexture = null
        textureId = null
    }

    /**
     * Releases the GPU texture asynchronously on the render thread; safe to call from teardown paths
     * that don't run on it (e.g. `DisplayScreen.unregister()` can fire from a Netty disconnect handler
     * — calling GL functions there aborts the JVM outright, not just throws).
     */
    fun closeAsync() {
        Minecraft.getInstance().execute { close() }
    }

    companion object {
        private val EMPTY_DIRECT: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
