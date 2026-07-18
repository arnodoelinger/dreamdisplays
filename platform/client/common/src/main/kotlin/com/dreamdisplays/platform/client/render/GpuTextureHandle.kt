package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.media.player.GpuTextureRef
//? if >=1.21.11 {
import com.mojang.blaze3d.textures.GpuTexture

//?}

/**
 * Platform implementation of [GpuTextureRef]: a thin wrapper carrying a Minecraft texture handle
 * across the media/player boundary so the player never references the rendering API directly.
 */
//? if >=1.21.11 {
@JvmInline
value class GpuTextureHandle(val texture: GpuTexture) : GpuTextureRef
//?} else
/*data class GpuTextureHandle(val textureId: Int, val width: Int, val height: Int) : GpuTextureRef*/
