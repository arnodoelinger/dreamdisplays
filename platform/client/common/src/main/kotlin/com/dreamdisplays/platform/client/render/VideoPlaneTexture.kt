package com.dreamdisplays.platform.client.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * One single-channel (RED8) plane of an I420 video frame: Y at full resolution, U and V at
 * half resolution. Three of these together back the GPU-side YUV -> RGB path: the planes are
 * uploaded raw and the display's fragment shader does the color conversion.
 *
 * Only constructed when [DisplayYuvRenderTypes.isSupported] is true (26.2+ replaced
 * `TextureFormat` with `GpuFormat` and reworked the pipeline builder).
 *
 * Must be constructed on the render thread.
 */
class VideoPlaneTexture(label: String, width: Int, height: Int) : AbstractTexture() {
    init {
        val device = RenderSystem.getDevice()
        texture = createRed8Texture(device, label, width, height)
        textureView = device.createTextureView(texture!!)
        sampler = DisplayYuvRenderTypes.planeSampler()
    }

    /** Reflectively creates a `RED8` GPU texture of [width] x [height] (handles the 26.2+ format rename). */
    private fun createRed8Texture(device: Any, label: String, width: Int, height: Int): GpuTexture {
        val textureFormatClass = Class.forName("com.mojang.blaze3d.textures.TextureFormat")

        @Suppress("UNCHECKED_CAST")
        val red8 = java.lang.Enum.valueOf(textureFormatClass as Class<out Enum<*>>, "RED8")
        val createTexture = device.javaClass.getMethod(
            "createTexture",
            String::class.java, Int::class.javaPrimitiveType, textureFormatClass,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        return createTexture.invoke(
            device,
            label,
            GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST,
            red8,
            width, height, 1, 1,
        ) as GpuTexture
    }
}
