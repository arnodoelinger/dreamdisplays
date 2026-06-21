package com.dreamdisplays.api.media


data class DecodedVideoFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val timestampUs: Long,
    val isKeyFrame: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedVideoFrame) return false
        return timestampUs == other.timestampUs && width == other.width && height == other.height
    }

    override fun hashCode(): Int = 31 * (31 * timestampUs.hashCode() + width) + height
}
