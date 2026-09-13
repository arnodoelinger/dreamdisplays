package com.dreamdisplays.api.media.model

import com.dreamdisplays.api.Unstable

/**
 * Platform-free pixel layout for decoded video frames; platform maps to concrete formats.
 *
 * @since 1.8.x
 */
@Unstable
enum class FramePixelFormat(val bytesPerPixel: Int) {
    /** Single-channel plane of an RGB frame. */
    RGB24(3),

    /** Four-channel plane of an RGBA frame. */
    RGBA32(4),

    /** Single-channel plane of an R8 frame. */
    R8(1),
}
