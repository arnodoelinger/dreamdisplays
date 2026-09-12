package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.render.texture.service.TextureUploaderFactory
import com.dreamdisplays.api.render.texture.service.TextureUploaderProvider

/** Supplies a [TextureUploaderFactory] backed by [AsyncTextureUploader]. */
object DefaultTextureUploaderProvider : TextureUploaderProvider {
    override fun create(): TextureUploaderFactory = TextureUploaderFactory { AsyncTextureUploader(stateCache = it) }
}
