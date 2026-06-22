package com.dreamdisplays.platform.client.render

import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Uploads raw video frames to Minecraft textures on `OpenGL` and backend-neutral renderers. */
object TextureUploadUtil {
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
        } catch (_: NoSuchMethodException) {}

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
