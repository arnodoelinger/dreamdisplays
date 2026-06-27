package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.media.FramePixelFormat
import com.dreamdisplays.api.media.player.FrameUploader
import com.dreamdisplays.api.media.player.GpuTextureRef
import com.mojang.blaze3d.textures.GpuTexture
import net.minecraft.client.Minecraft
import java.nio.ByteBuffer

/**
 * Minecraft GPU upload sink for one decode channel. Holds the persistent [AsyncTextureUploader]
 * PBO ring(s) and performs the actual texture uploads via [TextureUploadUtil], keeping all
 * rendering-API code out of the platform-agnostic media/player module.
 */
class GpuFrameUploader : FrameUploader {
    /** PBO uploader for the interleaved (RGB / RGBA) path; created on first use. */
    private var uploader: AsyncTextureUploader? = null

    /** Per-plane PBO uploaders for the planar (Y / U / V) path. */
    private val planeUploaders = arrayOfNulls<AsyncTextureUploader>(3)

    /** Scratch buffer reused for RGB -> RGBA expansion. */
    private var rgbaUploadBuffer: ByteBuffer? = null

    /** False while the window is minimized (no GL context to upload into). */
    override fun canUpload(): Boolean = !Minecraft.getInstance().window.isMinimized

    /** Uploads an interleaved [src] frame into [target] in the given [format]. */
    override fun uploadInterleaved(target: GpuTextureRef, src: ByteBuffer, format: FramePixelFormat): Boolean {
        val texture = (target as GpuTextureHandle).texture
        TextureUploadUtil.upload(
            texture = texture,
            src = src,
            w = texture.getWidth(0),
            h = texture.getHeight(0),
            format = format.toUploadFormat(),
            glUploader = { uploader ?: AsyncTextureUploader(stateCache = true).also { uploader = it } },
            rgbaScratch = rgbaUploadBuffer,
            setRgbaScratch = { rgbaUploadBuffer = it },
        )
        return true
    }

    /** Uploads the Y / U / V planes packed in [src] into their respective textures. */
    override fun uploadPlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, src: ByteBuffer): Boolean {
        var offset = 0
        for ((i, ref) in arrayOf(y, u, v).withIndex()) {
            val texture: GpuTexture = (ref as GpuTextureHandle).texture
            val planeBytes = texture.getWidth(0) * texture.getHeight(0)
            val view = src.duplicate()
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
        return true
    }

    /** Lazily creates the per-plane GL uploader for plane [i] (0 = Y, 1 = U, 2 = V). */
    private fun planeUploader(i: Int): AsyncTextureUploader =
        planeUploaders[i] ?: AsyncTextureUploader(stateCache = true).also { planeUploaders[i] = it }

    /** Releases all PBO uploaders and the scratch buffer. */
    override fun cleanup() {
        uploader?.cleanup()
        uploader = null
        for (i in planeUploaders.indices) {
            planeUploaders[i]?.cleanup()
            planeUploaders[i] = null
        }
        rgbaUploadBuffer = null
    }
}
