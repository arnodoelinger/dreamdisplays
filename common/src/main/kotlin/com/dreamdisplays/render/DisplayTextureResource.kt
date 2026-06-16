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
 * A second "pending" allocation can be staged alongside the live one ([allocatePending]) so a
 * resolution change (quality switch) can be decoded into fresh textures while the old ones keep
 * being rendered; [promotePending] then swaps it in atomically once the first new frame has landed,
 * so the picture never blanks during the switch.
 *
 * @param uuid the owning display's id, used to build a unique texture identifier.
 */
class DisplayTextureResource(private val uuid: UUID) {

    /** One complete set of GPU resources (either RGBA or the three YUV planes) plus its render types. */
    private class Allocation(
        val width: Int,
        val height: Int,
        val texture: DynamicTexture?,
        val textureId: Identifier?,
        val yPlane: AbstractTexture?,
        val uPlane: AbstractTexture?,
        val vPlane: AbstractTexture?,
        val planeIds: List<Identifier>,
        val renderType: RenderType,
        val fallbackRenderType: RenderType,
    ) {
        val isYuv: Boolean get() = yPlane != null

        /** All texture-manager ids backing this allocation (for release). */
        val allIds: List<Identifier> get() = listOfNotNull(textureId) + planeIds

        /** Closes the GPU textures and unregisters them from the texture manager. */
        fun release() {
            val manager = Minecraft.getInstance().textureManager
            texture?.close()
            listOfNotNull(yPlane, uPlane, vPlane).forEach { it.close() }
            allIds.forEach { runCatching { manager.release(it) } }
        }

        /** Approximate GPU bytes for budgeting; excludes driver overhead and texture-manager metadata. */
        fun estimatedBytes(): Long {
            val pixels = width.toLong() * height.toLong()
            return if (isYuv) {
                val chroma = ((width + 1) / 2L) * ((height + 1) / 2L)
                pixels + 2L * chroma
            } else {
                pixels * 4L
            }
        }
    }

    private var current: Allocation? = null

    // Read from the control thread (decode-target size) but written from the render thread, so the
    // handoff state must publish across threads.
    @Volatile private var pending: Allocation? = null

    @Volatile var width: Int = 0
        private set
    @Volatile var height: Int = 0
        private set

    val texture: DynamicTexture? get() = current?.texture
    val textureId: Identifier? get() = current?.textureId
    val renderType: RenderType? get() = current?.renderType

    val yPlane: AbstractTexture? get() = current?.yPlane
    val uPlane: AbstractTexture? get() = current?.uPlane
    val vPlane: AbstractTexture? get() = current?.vPlane

    /** True when the GPU-side YUV path backs this display (three planes instead of one RGBA texture). */
    val isYuv: Boolean get() = current?.isYuv == true

    /** True once either texture flavor has been allocated and the display can be drawn. */
    val hasTexture: Boolean get() = current != null

    /**
     * [RenderType] for the loading / error color quads. Identical to [renderType] in RGBA mode;
     * in YUV mode a plain unlit type over a white texture (the YUV shader would
     * misinterpret a flat color quad as chroma).
     */
    val fallbackRenderType: RenderType? get() = current?.fallbackRenderType

    /** True while a pending (new-resolution) allocation is staged, waiting for its first frame. */
    val hasPending: Boolean get() = pending != null

    /** Pixel dimensions of the pending allocation, or (0, 0) when none is staged. */
    val pendingWidth: Int get() = pending?.width ?: 0
    val pendingHeight: Int get() = pending?.height ?: 0
    val pendingTexture: DynamicTexture? get() = pending?.texture
    val pendingYPlane: AbstractTexture? get() = pending?.yPlane
    val pendingUPlane: AbstractTexture? get() = pending?.uPlane
    val pendingVPlane: AbstractTexture? get() = pending?.vPlane

    /** Approximate current + pending GPU texture bytes. */
    fun estimatedBytes(): Long = (current?.estimatedBytes() ?: 0L) + (pending?.estimatedBytes() ?: 0L)

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
     * Releases any existing textures (current and pending) and allocates fresh GPU textures and a
     * [RenderType] sized for [blockWidth] x [blockHeight] blocks at [qualityHeight] pixels. Must be
     * called on the render thread.
     */
    fun allocate(blockWidth: Int, blockHeight: Int, qualityHeight: Int) {
        discardPending()
        prepareDimensions(blockWidth, blockHeight, qualityHeight)
        current?.release()
        current = build(width, height)
    }

    /**
     * Stages a fresh allocation at the dimensions for [blockWidth] x [blockHeight] @ [qualityHeight]
     * without touching the live textures, so the current frame keeps rendering. Once the first
     * new-resolution frame has been uploaded into it, call [promotePending] to swap it in. Replaces
     * any previously staged pending allocation. Must be called on the render thread.
     */
    fun allocatePending(blockWidth: Int, blockHeight: Int, qualityHeight: Int) {
        discardPending()
        val w = ((blockWidth / blockHeight.toDouble()) * qualityHeight).toInt()
        pending = build(w, qualityHeight)
    }

    /**
     * Promotes the staged [pending] allocation to current, releasing the old one. The pending
     * textures must already hold a frame (otherwise the display would blank). Updates the public
     * [width] / [height] to the new dimensions. Must be called on the render thread.
     */
    fun promotePending() {
        val next = pending ?: return
        pending = null
        current?.release()
        current = next
        width = next.width
        height = next.height
    }

    /** Releases the staged pending allocation, if any, without touching the current one. Render thread only. */
    fun discardPending() {
        pending?.release()
        pending = null
    }

    /**
     * Cancels a staged handoff from any thread: clears the pending intent immediately (so the
     * decode-target size reverts to the live texture at once) and defers the GPU release to the
     * render thread. Used when a full session restart supersedes an in-flight quality handoff.
     */
    fun discardPendingAsync() {
        val p = pending ?: return
        pending = null
        Minecraft.getInstance().execute { p.release() }
    }

    /** Builds one allocation of [w] x [h] pixels in whichever pipeline mode is currently active. */
    private fun build(w: Int, h: Int): Allocation =
        if (DisplayYuvRenderTypes.active) buildYuv(w, h) else buildRgba(w, h)

    /** Builds the legacy single RGBA texture fed by CPU-converted frames. */
    private fun buildRgba(w: Int, h: Int): Allocation {
        val newTexture = DynamicTexture(
            { UUID.randomUUID().toString() },
            NativeImage(NativeImage.Format.RGBA, w, h, false),
        )
        val newId = Identifier.fromNamespaceAndPath(
            Initializer.MOD_ID,
            "screen-main-texture-$uuid-${UUID.randomUUID()}",
        )
        Minecraft.getInstance().textureManager.register(newId, newTexture)
        val rt = createRenderType(newId)
        return Allocation(
            width = w, height = h, texture = newTexture, textureId = newId,
            yPlane = null, uPlane = null, vPlane = null, planeIds = emptyList(),
            renderType = rt, fallbackRenderType = rt,
        )
    }

    /** Builds the three I420 plane textures consumed by the YUV fragment shader. */
    private fun buildYuv(w: Int, h: Int): Allocation {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val manager = Minecraft.getInstance().textureManager
        val suffix = "$uuid-${UUID.randomUUID()}"
        var y: AbstractTexture? = null
        var u: AbstractTexture? = null
        var v: AbstractTexture? = null
        val ids = listOf("y" to (w to h), "u" to (cw to ch), "v" to (cw to ch)).map { (plane, dims) ->
            val id = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "screen-$plane-plane-$suffix")
            val tex = DisplayYuvRenderTypes.createPlaneTexture("dreamdisplays $plane plane $uuid", dims.first, dims.second)
            manager.register(id, tex)
            when (plane) {
                "y" -> y = tex
                "u" -> u = tex
                else -> v = tex
            }
            id
        }
        return Allocation(
            width = w, height = h, texture = null, textureId = null,
            yPlane = y, uPlane = u, vPlane = v, planeIds = ids,
            renderType = DisplayYuvRenderTypes.create(ids[0], ids[1], ids[2]),
            fallbackRenderType = DisplayYuvRenderTypes.createFallback(),
        )
    }

    /** Closes the current and pending textures and unregisters them, leaving the resource empty. */
    fun release() {
        discardPending()
        current?.release()
        current = null
    }

    /** Releases the GPU textures asynchronously on the render thread; safe to call during teardown. */
    fun releaseAsync() {
        val ids = (current?.allIds.orEmpty()) + (pending?.allIds.orEmpty())
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
