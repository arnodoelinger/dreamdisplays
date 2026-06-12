package com.dreamdisplays.render

import com.mojang.blaze3d.platform.NativeImage
import org.lwjgl.opengl.GL11

/** Pixel layout of decoded video frames handed to the texture uploader. */
enum class UploadPixelFormat(
    val bytesPerPixel: Int,
    val glFormat: Int,
    val nativeImageFormat: NativeImage.Format,
    val unpackAlignment: Int,
) {
    RGB24(3, GL11.GL_RGB, NativeImage.Format.RGB, 1),
    RGBA32(4, GL11.GL_RGBA, NativeImage.Format.RGBA, 4),
}
