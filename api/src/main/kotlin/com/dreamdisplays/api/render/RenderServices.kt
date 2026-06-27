package com.dreamdisplays.api.render

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.runtime.ServiceKey
import com.dreamdisplays.api.runtime.serviceKey

/**
 * Render service keys.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
object RenderServices {
    /** API surface renderer used to render registered [RenderSurface] instances. */
    val DISPLAY_RENDERER: ServiceKey<DisplayRenderer> = serviceKey("dreamdisplays:display_renderer")

    /** Factory for creating texture uploaders on a render context. */
    val TEXTURE_UPLOADER_FACTORY: ServiceKey<TextureUploaderFactory> =
        serviceKey("dreamdisplays:texture_uploader_factory")
}
