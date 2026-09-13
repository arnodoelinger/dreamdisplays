package com.dreamdisplays.api.render.texture.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.sink.model.DecodedVideoFrame
import com.dreamdisplays.api.render.texture.model.TextureHandle

/**
 * Uploads decoded video frames into platform-owned textures.
 *
 * @since 1.8.x
 */
@Unstable
interface TextureUploaderService : AutoCloseable {
    /** True when the implementation can perform upload work asynchronously. */
    val supportsAsync: Boolean

    /** Largest supported texture dimension in pixels. */
    val maxTextureSize: Int

    /** Uploads [frame] and returns the texture handle that now contains it. */
    fun upload(frame: DecodedVideoFrame): TextureHandle

    /** Releases the platform texture represented by [handle]. */
    fun release(handle: TextureHandle)
}
