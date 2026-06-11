package com.dreamdisplays.render.api

import com.dreamdisplays.media.api.DecodedVideoFrame

interface TextureUploader : AutoCloseable {
    val supportsAsync: Boolean
    val maxTextureSize: Int

    fun upload(frame: DecodedVideoFrame): TextureHandle
    fun release(handle: TextureHandle)
}
