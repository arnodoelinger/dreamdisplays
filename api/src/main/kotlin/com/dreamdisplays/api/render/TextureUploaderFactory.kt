package com.dreamdisplays.api.render

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Creates [TextureUploader] instances. Uploaders are per-GL-context objects (each popout window,
 * PiP overlay, and frame pipe owns its own), so the registry exposes this factory rather than a
 * single shared uploader instance.
 */
@DreamDisplaysUnstableApi
fun interface TextureUploaderFactory {
    /** @param stateCache true to route GL calls through Minecraft's cached `GlStateManager`. */
    fun create(stateCache: Boolean): TextureUploader
}
