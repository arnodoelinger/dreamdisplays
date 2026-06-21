package com.dreamdisplays.api.render

import com.dreamdisplays.api.media.DecodedVideoFrame

interface TextureUploader : AutoCloseable {
    val supportsAsync: Boolean
    val maxTextureSize: Int

    fun upload(frame: DecodedVideoFrame): TextureHandle
    fun release(handle: TextureHandle)
}
