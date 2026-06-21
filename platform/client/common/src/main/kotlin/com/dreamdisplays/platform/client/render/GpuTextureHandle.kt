package com.dreamdisplays.platform.client.render

import com.dreamdisplays.media.player.api.GpuTextureRef
import com.mojang.blaze3d.textures.GpuTexture

/**
 * Platform implementation of [GpuTextureRef]: a thin wrapper carrying a Minecraft [GpuTexture]
 * across the media/player boundary so the player never references the rendering API directly.
 */
@JvmInline
value class GpuTextureHandle(val texture: GpuTexture) : GpuTextureRef
