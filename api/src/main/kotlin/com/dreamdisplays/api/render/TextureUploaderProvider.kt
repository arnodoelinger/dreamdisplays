package com.dreamdisplays.api.render

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Supplies the [TextureUploaderFactory].
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
fun interface TextureUploaderProvider {
    /** Creates the factory instance. */
    fun create(): TextureUploaderFactory
}
