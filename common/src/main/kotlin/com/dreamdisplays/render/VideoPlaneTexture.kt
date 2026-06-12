package com.dreamdisplays.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.TextureFormat
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * One single-channel (RED8) plane of an I420 video frame: Y at full resolution, U and V at
 * half resolution. Three of these together back the GPU-side YUV -> RGB path: the planes are
 * uploaded raw and the display's fragment shader does the color conversion.
 *
 * Only constructed when [DisplayYuvRenderTypes.isSupported] is true (26.2+ replaced
 * [TextureFormat] with `GpuFormat` and reworked the pipeline builder, so the YUV path is
 * disabled there until the module compiles against the new API).
 *
 * Must be constructed on the render thread.
 */
class VideoPlaneTexture(label: String, width: Int, height: Int) : AbstractTexture() {
    init {
        val device = RenderSystem.getDevice()
        texture = device.createTexture(
            label,
            GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST,
            TextureFormat.RED8,
            width, height, 1, 1,
        )
        textureView = device.createTextureView(texture!!)
        sampler = DisplayYuvRenderTypes.planeSampler()
    }
}
