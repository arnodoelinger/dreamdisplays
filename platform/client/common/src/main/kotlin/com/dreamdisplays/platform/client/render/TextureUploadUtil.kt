package com.dreamdisplays.platform.client.render

//? if >=1.21.11 {
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
//?}
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import org.lwjgl.opengl.GL11
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Uploads raw video frames to Minecraft textures on `OpenGL` and backend-neutral renderers. */
object TextureUploadUtil {
    /**
     * Switches [tex] from Minecraft's default nearest-neighbor sampling to bilinear. Video frames
     * and thumbnails are almost always scaled to fit their on-screen box, so without this they
     * look blocky rather than smooth. No-op on backends where the raw GL texture id isn't
     * reachable (e.g. VulkanMod).
     */
    fun applyBilinearFilter(tex: DynamicTexture) {
        if (!RenderBackendCompat.canUseDirectOpenGl()) return
        //? if >=1.21.11 {
        val glId = (tex.getTexture() as? GlTexture)?.glId() ?: return
        //?} else
        /*val glId = tex.getId()*/
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
    }

    /** Uploads raw pixels into a [DynamicTexture] across both legacy GL-id and new GpuTexture APIs. */
    fun uploadDynamicTexture(
        texture: DynamicTexture,
        src: ByteBuffer,
        w: Int,
        h: Int,
        format: UploadPixelFormat,
        glUploader: () -> AsyncTextureUploader,
        rgbaScratch: ByteBuffer?,
        setRgbaScratch: (ByteBuffer) -> Unit,
    ) {
        //? if >=1.21.11 {
        upload(
            texture = texture.getTexture(),
            src = src,
            w = w,
            h = h,
            format = format,
            glUploader = glUploader,
            rgbaScratch = rgbaScratch,
            setRgbaScratch = setRgbaScratch,
        )
        //?} else
        /*glUploader().upload(texture.getId(), src, w, h, format)*/
    }

    //? if >=1.21.11 {
    /** Uploads a raw RGB24 video frame to a Minecraft texture. */
    fun uploadRgb(
        texture: GpuTexture,
        src: ByteBuffer,
        w: Int,
        h: Int,
        glUploader: () -> AsyncTextureUploader,
        rgbaScratch: ByteBuffer?,
        setRgbaScratch: (ByteBuffer) -> Unit,
    ) = upload(
        texture = texture,
        src = src,
        w = w,
        h = h,
        format = UploadPixelFormat.RGB24,
        glUploader = glUploader,
        rgbaScratch = rgbaScratch,
        setRgbaScratch = setRgbaScratch,
    )

    /** Uploads texture data to a Minecraft texture. */
    fun upload(
        texture: GpuTexture,
        src: ByteBuffer,
        w: Int,
        h: Int,
        format: UploadPixelFormat,
        glUploader: () -> AsyncTextureUploader,
        rgbaScratch: ByteBuffer?,
        setRgbaScratch: (ByteBuffer) -> Unit,
    ) {
        if (texture.isClosed) return
        if (texture is GlTexture && RenderBackendCompat.canUseDirectOpenGl()) {
            glUploader().upload(texture.glId(), src, texture.getWidth(0), texture.getHeight(0), format)
            return
        }

        // RGBA32 and R8 are uploaded as-is; only RGB24 needs the expansion pass below
        if (format != UploadPixelFormat.RGB24) {
            writeToTexture(texture, src, w, h, format.nativeImageFormat)
            return
        }

        val rgbaSize = w * h * 4
        val rgba = rgbaScratch?.takeIf { it.capacity() >= rgbaSize }
            ?: ByteBuffer.allocateDirect(rgbaSize).order(ByteOrder.nativeOrder()).also(setRgbaScratch)
        rgbToRgba(src, rgba, w, h)
        writeToTexture(texture, rgba, w, h, NativeImage.Format.RGBA)
    }

    /** Write to a Minecraft texture using the `writeToTexture` method. */
    private fun writeToTexture(texture: GpuTexture, pixels: ByteBuffer, w: Int, h: Int, format: NativeImage.Format) {
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val encoderClass = encoder.javaClass

        try {
            encoderClass
                .getMethod(
                    "writeToTexture",
                    GpuTexture::class.java,
                    ByteBuffer::class.java,
                    NativeImage.Format::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invokeOrThrowTarget(encoder, texture, pixels, format, 0, 0, 0, 0, w, h)
            return
        } catch (_: NoSuchMethodException) {
        }

        encoderClass
            .getMethod(
                "writeToTexture",
                GpuTexture::class.java,
                ByteBuffer::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .invokeOrThrowTarget(encoder, texture, pixels, 0, 0, 0, 0, w, h)
    }

    /** Invokes the given method on the given target. Throws the target exception if any. */
    private fun Method.invokeOrThrowTarget(target: Any, vararg args: Any?) {
        try {
            invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
    //?}

    /** RGB24 -> RGBA32 conversion. */
    private fun rgbToRgba(src: ByteBuffer, dst: ByteBuffer, w: Int, h: Int) {
        val size = w * h * 3
        if (size <= 0 || src.remaining() < size) return

        val savedLimit = src.limit()
        val savedPos = src.position()
        src.limit(savedPos + size)
        dst.clear()
        while (src.hasRemaining()) {
            dst.put(src.get())
            dst.put(src.get())
            dst.put(src.get())
            dst.put(0xFF.toByte())
        }
        dst.flip()
        src.limit(savedLimit)
        src.position(savedPos)
    }
}
