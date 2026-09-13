package com.dreamdisplays.api.render.service.keys

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.render.backend.service.RenderSurface
import com.dreamdisplays.api.render.service.DisplayRenderer
import com.dreamdisplays.api.render.texture.service.TextureUploaderFactory
import com.dreamdisplays.api.runtime.registry.model.ServiceKey
import com.dreamdisplays.api.runtime.registry.model.serviceKey

/**
 * Render service keys.
 *
 * @since 1.8.x
 */
@Unstable
object RenderServices {
    /** API surface renderer used to render registered [RenderSurface] instances. */
    val DISPLAY_RENDERER: ServiceKey<DisplayRenderer> = serviceKey("dreamdisplays:display_renderer")

    /** Factory for creating texture uploaders on a render context. */
    val TEXTURE_UPLOADER_FACTORY: ServiceKey<TextureUploaderFactory> =
        serviceKey("dreamdisplays:texture_uploader_factory")
}
