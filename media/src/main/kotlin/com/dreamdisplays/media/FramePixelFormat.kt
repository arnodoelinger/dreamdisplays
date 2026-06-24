package com.dreamdisplays.media

/**
 * Platform-free pixel layout of a decoded video frame handed from the media player to the
 * render layer. The Minecraft client maps this to its concrete GL / `NativeImage` formats.
 */
enum class FramePixelFormat(val bytesPerPixel: Int) {
    RGB24(3),
    RGBA32(4),

    /** Single-channel plane of an I420 frame (Y, U, or V). */
    R8(1),
}
