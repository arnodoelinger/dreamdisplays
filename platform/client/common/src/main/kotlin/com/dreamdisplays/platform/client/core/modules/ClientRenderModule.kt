package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.api.render.RenderServices
import com.dreamdisplays.api.runtime.DreamDisplaysModule
import com.dreamdisplays.api.runtime.ModuleContext
import com.dreamdisplays.api.runtime.register
import com.dreamdisplays.platform.client.render.*

/** Installs client render services, API surface renderer, and texture uploader factory. */
object ClientRenderModule : DreamDisplaysModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplays:client_render"

    /** Installs the render service, API surface renderer, and texture uploader factory. */
    override fun install(context: ModuleContext) {
        val services = context.services
        services.register<ClientRenderService>(ScreenRenderer)
        services.register(RenderServices.DISPLAY_RENDERER, DefaultRendererProvider.create())
        services.register(RenderServices.TEXTURE_UPLOADER_FACTORY, DefaultTextureUploaderProvider.create())
        services.register<RenderHook>(RenderHook { renderContext ->
            services.getOrNull(RenderServices.DISPLAY_RENDERER)?.takeIf { it.registeredCount > 0 }
                ?.renderAll(renderContext)
        })
    }
}
