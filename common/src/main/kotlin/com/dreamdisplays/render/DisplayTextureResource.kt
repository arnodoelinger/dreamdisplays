package com.dreamdisplays.render

import com.dreamdisplays.Initializer
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * Owns the per-display GPU resources, the [DynamicTexture] the video frames are uploaded into, its registered
 * [Identifier], and the [RenderType] that samples it and their allocation / release lifecycle.
 *
 * Pulled out of [com.dreamdisplays.displays.DisplayScreen] so the screen no longer mixes Minecraft texture
 * management with playback and sync state. [width]/[height] are the texture's pixel dimensions, derived from the
 * screen's block aspect ratio and target quality.
 *
 * @param uuid the owning display's id, used to build a unique texture identifier.
 */
class DisplayTextureResource(private val uuid: UUID) {
    var texture: DynamicTexture? = null
        private set
    var textureId: Identifier? = null
        private set
    var renderType: RenderType? = null
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /**
     * Sets the texture dimensions for a screen of [blockWidth] x [blockHeight] blocks rendered at [qualityHeight]
     * pixels tall, without touching the GPU. Used to pre-seed sizes before the actual allocation happens on the
     * render thread.
     */
    fun prepareDimensions(blockWidth: Int, blockHeight: Int, qualityHeight: Int) {
        width = ((blockWidth / blockHeight.toDouble()) * qualityHeight).toInt()
        height = qualityHeight
    }

    /**
     * Releases any existing texture and allocates a fresh [DynamicTexture] and [RenderType] sized for
     * [blockWidth] x [blockHeight] blocks at [qualityHeight] pixels. Must be called on the render thread.
     */
    fun allocate(blockWidth: Int, blockHeight: Int, qualityHeight: Int) {
        prepareDimensions(blockWidth, blockHeight, qualityHeight)
        release()
        val newTexture = DynamicTexture(
            { UUID.randomUUID().toString() },
            NativeImage(NativeImage.Format.RGBA, width, height, false),
        )
        val newId = Identifier.fromNamespaceAndPath(
            Initializer.MOD_ID,
            "screen-main-texture-$uuid-${UUID.randomUUID()}",
        )
        Minecraft.getInstance().textureManager.register(newId, newTexture)
        texture = newTexture
        textureId = newId
        renderType = createRenderType(newId)
    }

    /** Closes the current texture and unregisters it from the texture manager, leaving the resource empty. */
    fun release() {
        texture?.let { t ->
            t.close()
            textureId?.let { Minecraft.getInstance().textureManager.release(it) }
        }
        texture = null
        textureId = null
    }

    /** Releases the GPU texture asynchronously on the render thread; safe to call during teardown. */
    fun releaseAsync() {
        val id = textureId ?: return
        val mc = Minecraft.getInstance()
        mc.execute {
            try {
                mc.textureManager.release(id)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        /** Creates a custom [RenderType] that samples texture [id] through the solid-block pipeline. */
        private fun createRenderType(id: Identifier): RenderType = RenderType.create(
            "dream-displays",
            RenderSetup.builder(RenderPipelines.SOLID_BLOCK)
                .withTexture("Sampler0", id)
                .affectsCrumbling()
                .useLightmap()
                .createRenderSetup(),
        )
    }
}
