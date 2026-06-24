package com.dreamdisplays.api.render

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.media.sink.DecodedVideoFrame

/**
 * Uploads decoded video frames into platform-owned textures.
 *
 * @since 1.8.0
 */
@DreamDisplaysUnstableApi
interface TextureUploader : AutoCloseable {
    /** True when the implementation can perform upload work asynchronously. */
    val supportsAsync: Boolean

    /** Largest supported texture dimension in pixels. */
    val maxTextureSize: Int

    /** Uploads [frame] and returns the texture handle that now contains it. */
    fun upload(frame: DecodedVideoFrame): TextureHandle

    /** Releases the platform texture represented by [handle]. */
    fun release(handle: TextureHandle)
}
