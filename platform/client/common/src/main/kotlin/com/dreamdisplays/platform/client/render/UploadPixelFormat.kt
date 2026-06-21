package com.dreamdisplays.platform.client.render

import com.dreamdisplays.media.FramePixelFormat
import com.mojang.blaze3d.platform.NativeImage
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/** Pixel layout of decoded video frames handed to the texture uploader. */
enum class UploadPixelFormat(
    val bytesPerPixel: Int,
    val glFormat: Int,
    val nativeImageFormat: NativeImage.Format,
    val unpackAlignment: Int,
) {
    RGB24(3, GL11.GL_RGB, NativeImage.Format.RGB, 1),
    RGBA32(4, GL11.GL_RGBA, NativeImage.Format.RGBA, 4),

    /** Single-channel plane of an I420 frame (Y, U, or V), uploaded into a RED8 texture. */
    R8(1, GL30.GL_RED, NativeImage.Format.LUMINANCE, 1),
}

/** Maps the platform-free [FramePixelFormat] produced by the media player to its GL counterpart. */
fun FramePixelFormat.toUploadFormat(): UploadPixelFormat = when (this) {
    FramePixelFormat.RGB24 -> UploadPixelFormat.RGB24
    FramePixelFormat.RGBA32 -> UploadPixelFormat.RGBA32
    FramePixelFormat.R8 -> UploadPixelFormat.R8
}
