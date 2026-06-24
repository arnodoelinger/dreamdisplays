package com.dreamdisplays.api.media.sink

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Decoded video frame payload before GPU upload. [data] is owned by the frame producer.
 *
 * @since 1.8.0
 */
@DreamDisplaysUnstableApi
data class DecodedVideoFrame(
    /** Pixel bytes in the decoder-selected format. */
    val data: ByteArray,

    /** Frame width in pixels. */
    val width: Int,

    /** Frame height in pixels. */
    val height: Int,

    /** Presentation timestamp in microseconds. */
    val timestampUs: Long,

    /** True if this frame can be decoded without earlier frames. */
    val isKeyFrame: Boolean,
) {
    /** Compares this frame to another for equality. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedVideoFrame) return false
        return timestampUs == other.timestampUs && width == other.width && height == other.height
    }

    /** Computes a hash code for this frame. */
    override fun hashCode(): Int = 31 * (31 * timestampUs.hashCode() + width) + height
}
