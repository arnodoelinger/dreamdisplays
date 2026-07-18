package com.dreamdisplays.platform.client.render

//? if >=1.21.11 {
//?}
import com.dreamdisplays.api.media.FramePixelFormat
import com.dreamdisplays.api.media.player.FrameUploader
import com.dreamdisplays.api.media.player.GpuTextureRef
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
    override fun canUpload(): Boolean =
        //? if >=1.21.11 {
        !Minecraft.getInstance().window.isMinimized
        //?} else
        /*true*/

    /** Uploads an interleaved [src] frame into [target] in the given [format]. */
    override fun uploadInterleaved(target: GpuTextureRef, src: ByteBuffer, format: FramePixelFormat): Boolean {
        //? if >=1.21.11 {
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
        //?} else
        /*val texture = target as GpuTextureHandle
        glUploader().upload(texture.textureId, src, texture.width, texture.height, format.toUploadFormat())*/
        return true
    }

    /** Uploads the Y / U / V planes packed in [src] into their respective textures in one PBO pass. */
    override fun uploadPlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, src: ByteBuffer): Boolean {
        //? if >=1.21.11 {
        TextureUploadUtil.uploadPlanar(
            y = (y as GpuTextureHandle).texture,
            u = (u as GpuTextureHandle).texture,
            v = (v as GpuTextureHandle).texture,
            src = src,
            glUploader = { planeUploader(0) },
        )
        return true
        //?} else
        /*return false*/
    }

    /** Lazily creates the interleaved GL uploader. */
    private fun glUploader(): AsyncTextureUploader =
        uploader ?: AsyncTextureUploader(stateCache = true).also { uploader = it }

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
