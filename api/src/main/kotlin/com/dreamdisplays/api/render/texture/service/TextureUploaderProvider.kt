package com.dreamdisplays.api.render.texture.service

import com.dreamdisplays.api.Unstable

/**
 * Supplies the [TextureUploaderFactory].
 *
 * @since 1.8.x
 */
@Unstable
fun interface TextureUploaderProvider {
    /** Creates the factory instance. */
    fun create(): TextureUploaderFactory
}
