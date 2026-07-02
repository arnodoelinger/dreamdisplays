package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.Initializer
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import java.util.UUID

/**
 * Owns the per-display GPU resources and their allocation / release lifecycle. Depending on the
 * pipeline mode this is either a single RGBA [DynamicTexture] (frames converted on the CPU) or
 * three RED8 [VideoPlaneTexture] planes (raw I420 planes converted in the fragment shader, see
 * [DisplayYuvRenderTypes]), plus the [RenderType] that samples them.
 *
 * Pulled out of [com.dreamdisplays.platform.client.displays.DisplayScreen] so the screen no longer mixes Minecraft texture
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
        /** If YUV is active, the Y plane is always present. */
        val isYuv: Boolean get() = yPlane != null

        /** All texture-manager ids backing this allocation (for release). */
        val allIds: List<Identifier> get() = listOfNotNull(textureId) + planeIds

        /** Set once [release] has run, so a deferred cancel racing a promote can never double-free. */
        private var released = false

        /** Closes the GPU textures and unregisters them from the texture manager. Render thread only; idempotent. */
        fun release() {
            if (released) return
            released = true
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

    /** Allocated GPU resources, or null if none are allocated. */
    @Volatile
    private var current: Allocation? = null

    /**
     * Pending allocation, or null if none is staged. This is the new-resolution allocation that
     * will be swapped in once the first frame has landed.
     */
    @Volatile
    private var pending: Allocation? = null

    /** Width of the current allocation, or 0 if none is allocated. */
    @Volatile
    var width: Int = 0; private set

    /** Height of the current allocation, or 0 if none is allocated. */
    @Volatile
    var height: Int = 0; private set

    /** Current GPU texture, or null if none is allocated. */
    val texture: DynamicTexture? get() = current?.texture

    /** Current GPU texture identifier, or null if none is allocated. */
    val textureId: Identifier? get() = current?.textureId

    /** [RenderType] used to draw the live video frame, or null if none is allocated. */
    val renderType: RenderType? get() = current?.renderType

    /**
     * Three I420 plane textures, or null if none is allocated.
     */
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

    /** Approximate current and pending GPU texture bytes. */
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
        // The render thread may promote p to current concurrently; release it only if it never went live.
        Minecraft.getInstance().execute { if (p !== current) p.release() }
    }

    /** Builds one allocation of [w] x [h] pixels in whichever pipeline mode is currently active. */
    private fun build(w: Int, h: Int): Allocation =
        //? if >=1.21.11 {
        if (DisplayYuvRenderTypes.active) buildYuv(w, h) else buildRgba(w, h)
        //?} else
        /*buildRgba(w, h)*/

    /** Builds the legacy single RGBA texture fed by CPU-converted frames. */
    private fun buildRgba(w: Int, h: Int): Allocation {
        //? if >=1.21.11 {
        val newTexture = DynamicTexture(
            { UUID.randomUUID().toString() },
            NativeImage(NativeImage.Format.RGBA, w, h, false),
        )
        //?} else
        /*val newTexture = DynamicTexture(NativeImage(NativeImage.Format.RGBA, w, h, false))*/
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

    //? if >=1.21.11 {
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
            val id = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "screen-$plane-plane-$suffix.")
            val tex =
                DisplayYuvRenderTypes.createPlaneTexture("dreamdisplays $plane plane $uuid.", dims.first, dims.second)
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
    //?}

    /** Closes the current and pending textures and unregisters them, leaving the resource empty. */
    fun release() {
        discardPending()
        current?.release()
        current = null
    }

    /**
     * Releases the GPU textures asynchronously on the render thread; safe to call during teardown.
     * Clears [current] / [pending] immediately so [hasTexture] and the getters stop handing out
     * textures that are about to be freed.
     */
    fun releaseAsync() {
        val c = current
        val p = pending
        current = null
        pending = null
        if (c == null && p == null) return
        Minecraft.getInstance().execute {
            c?.release()
            p?.release()
        }
    }

    companion object {
        /** Creates a custom unlit [RenderType] that samples texture [id] without block light / fog. */
        private fun createRenderType(id: Identifier): RenderType = DisplayUnlitRenderTypes.create("dream-displays", id)
    }
}
