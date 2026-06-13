package com.dreamdisplays.render

import com.dreamdisplays.Initializer
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * Owns the per-display GPU resources and their allocation / release lifecycle. Depending on the
 * pipeline mode this is either a single RGBA [DynamicTexture] (frames converted on the CPU) or
 * three RED8 [VideoPlaneTexture] planes (raw I420 planes converted in the fragment shader, see
 * [DisplayYuvRenderTypes]), plus the [RenderType] that samples them.
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

    var yPlane: AbstractTexture? = null
        private set
    var uPlane: AbstractTexture? = null
        private set
    var vPlane: AbstractTexture? = null
        private set
    private var planeIds: List<Identifier> = emptyList()

    /** True when the GPU-side YUV path backs this display (three planes instead of one RGBA texture). */
    val isYuv: Boolean get() = yPlane != null

    /** True once either texture flavor has been allocated and the display can be drawn. */
    val hasTexture: Boolean get() = texture != null || yPlane != null

    /**
     * [RenderType] for the loading / error color quads. Identical to [renderType] in RGBA mode;
     * in YUV mode a plain unlit type over a white texture (the YUV shader would
     * misinterpret a flat color quad as chroma).
     */
    var fallbackRenderType: RenderType? = null
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
     * Releases any existing texture and allocates fresh GPU textures and a [RenderType] sized for
     * [blockWidth] x [blockHeight] blocks at [qualityHeight] pixels. Must be called on the render thread.
     */
    fun allocate(blockWidth: Int, blockHeight: Int, qualityHeight: Int) {
        prepareDimensions(blockWidth, blockHeight, qualityHeight)
        release()
        if (DisplayYuvRenderTypes.active) allocateYuv() else allocateRgba()
    }

    /** Allocates the legacy single RGBA texture fed by CPU-converted frames. */
    private fun allocateRgba() {
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
        fallbackRenderType = renderType
    }

    /** Allocates the three I420 plane textures consumed by the YUV fragment shader. */
    private fun allocateYuv() {
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val manager = Minecraft.getInstance().textureManager
        val suffix = "$uuid-${UUID.randomUUID()}"
        val ids = listOf("y" to (width to height), "u" to (cw to ch), "v" to (cw to ch)).map { (plane, dims) ->
            val id = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "screen-$plane-plane-$suffix")
            val tex = DisplayYuvRenderTypes.createPlaneTexture("dreamdisplays $plane plane $uuid", dims.first, dims.second)
            manager.register(id, tex)
            when (plane) {
                "y" -> yPlane = tex
                "u" -> uPlane = tex
                else -> vPlane = tex
            }
            id
        }
        planeIds = ids
        renderType = DisplayYuvRenderTypes.create(ids[0], ids[1], ids[2])
        fallbackRenderType = DisplayYuvRenderTypes.createFallback()
    }

    /** Closes the current textures and unregisters them from the texture manager, leaving the resource empty. */
    fun release() {
        val manager = Minecraft.getInstance().textureManager
        texture?.let { t ->
            t.close()
            textureId?.let { manager.release(it) }
        }
        texture = null
        textureId = null
        listOfNotNull(yPlane, uPlane, vPlane).forEach { it.close() }
        planeIds.forEach { runCatching { manager.release(it) } }
        yPlane = null
        uPlane = null
        vPlane = null
        planeIds = emptyList()
        renderType = null
        fallbackRenderType = null
    }

    /** Releases the GPU textures asynchronously on the render thread; safe to call during teardown. */
    fun releaseAsync() {
        val ids = listOfNotNull(textureId) + planeIds
        if (ids.isEmpty()) return
        val mc = Minecraft.getInstance()
        mc.execute {
            for (id in ids) {
                try {
                    mc.textureManager.release(id)
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        /** Creates a custom unlit [RenderType] that samples texture [id] without block light/fog. */
        private fun createRenderType(id: Identifier): RenderType = DisplayUnlitRenderTypes.create("dream-displays", id)
    }
}
