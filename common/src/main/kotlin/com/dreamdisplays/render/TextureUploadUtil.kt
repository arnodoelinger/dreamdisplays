package com.dreamdisplays.render

import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Uploads raw video frames to Minecraft textures on `OpenGL` and backend-neutral renderers. */
object TextureUploadUtil {
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
        if (texture is GlTexture) {
            glUploader().upload(texture.glId(), src, texture.getWidth(0), texture.getHeight(0), format)
            return
        }

        if (format == UploadPixelFormat.RGBA32) {
            writeToTexture(texture, src, w, h, format.nativeImageFormat)
            return
        }

        val rgbaSize = w * h * 4
        val rgba = rgbaScratch?.takeIf { it.capacity() >= rgbaSize }
            ?: ByteBuffer.allocateDirect(rgbaSize).order(ByteOrder.nativeOrder()).also(setRgbaScratch)
        rgbToRgba(src, rgba, w, h)
        writeToTexture(texture, rgba, w, h, NativeImage.Format.RGBA)
    }

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

    private fun java.lang.reflect.Method.invokeOrThrowTarget(target: Any, vararg args: Any?) {
        try {
            invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

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
