package com.dreamdisplays.api.render

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Supplies the [DisplayRenderer] runtime renders registered surfaces with, so module
 * installers depend on this contract instead of the concrete renderer implementation in the platform module.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
fun interface RendererProvider {
    /** Creates the renderer instance. */
    fun create(): DisplayRenderer
}
