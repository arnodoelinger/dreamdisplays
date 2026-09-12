package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.render.service.DisplayRenderer
import com.dreamdisplays.api.render.backend.service.RendererProvider

/** Supplies the GPU-backed [DisplayRenderer] used to render registered surfaces. */
object DefaultRendererProvider : RendererProvider {
    override fun create(): DisplayRenderer = DefaultDisplayRenderer()
}
