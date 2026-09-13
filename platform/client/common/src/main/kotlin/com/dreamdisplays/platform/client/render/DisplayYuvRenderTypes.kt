package com.dreamdisplays.platform.client.render

import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.dreamdisplays.platform.client.Initializer
//? if >=1.21.11 {
import com.mojang.blaze3d.pipeline.RenderPipeline
//?}
import com.mojang.blaze3d.platform.NativeImage
//? if >=1.21.11 {
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
//?}
import net.minecraft.client.Minecraft
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=1.21.11 {
import net.minecraft.resources.Identifier

//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/**
 * GPU-side YUV -> RGB path: a custom [RenderPipeline] that samples the three I420 planes (Y / U / V as RED8 textures)
 * and converts to RGB in the fragment shader.
 */
object DisplayYuvRenderTypes {
    //? if >=1.21.11 {
    /** Shared linear / clamp sampler used by all video planes. */
    private var sharedPlaneSampler: GpuSampler? = null

    /**
     * Lazily creates the shared linear / clamp sampler used by all video planes. The device does not cache samplers
     * itself, so this avoids allocating one per texture.
     */
    fun planeSampler(): GpuSampler =
        sharedPlaneSampler ?: com.mojang.blaze3d.systems.RenderSystem.getDevice().createSampler(
            AddressMode.CLAMP_TO_EDGE,
            AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR,
            FilterMode.LINEAR,
            1, java.util.OptionalDouble.empty(),
        ).also { sharedPlaneSampler = it }

    /** Sampler names bound to the Y / U / V planes. */
    private const val SAMPLER_Y = "Sampler0"
    private const val SAMPLER_U = "Sampler1"
    private const val SAMPLER_V = "Sampler3"

    /**
     * True when the runtime exposes the 26.1-era pipeline-builder API. 26.2 replaced
     * `withSampler` / `withUniform` / `TextureFormat`, so that path goes through [Yuv262Reflect].
     */
    val isSupported: Boolean by lazy {
        runCatching {
            RenderPipeline.Builder::class.java.getMethod("withSampler", String::class.java)
            Class.forName("com.mojang.blaze3d.textures.TextureFormat")
            true
        }.getOrDefault(false)
    }

    /**
     * Single decision point for the GPU-YUV mode: the native library must produce planar frames and the runtime must
     * support the YUV pipeline. A shader pack rules it out, because displays are also drawn inside the level pass
     * (that is what puts them behind water and glass) and no pack knows how to read the three planes: it would
     * swap this pipeline's program out and sample the Y plane as if it were RGB.
     */
    val active: Boolean
        get() = !ShaderPackCompat.isShaderPackActive
                && (isSupported || Yuv262Reflect.isAvailable)
                && NativeMedia.yuvGpuEnabled

    /** Creates one RED8 plane texture through the built-in (26.1-era) or reflective 26.2 API. */
    fun createPlaneTexture(
        label: String,
        width: Int,
        height: Int
    ): net.minecraft.client.renderer.texture.AbstractTexture =
        if (isSupported) VideoPlaneTexture(label, width, height)
        else Yuv262Reflect.createPlaneTexture(label, width, height)

    /** Compiled lazily by the backend on first draw; shared by every YUV display. */
    private val pipeline: RenderPipeline by lazy {
        if (!isSupported) return@lazy Yuv262Reflect.createPipeline()
        RenderPipelineCompat.createDisplayPipeline(
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "pipeline/display_yuv"),
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "core/display_fog"),
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "core/display_yuv"),
            listOf(SAMPLER_Y, SAMPLER_U, SAMPLER_V),
        )
    }

    /** Creates the [RenderType] drawing a display through the YUV pipeline from its three plane textures. */
    fun create(yId: Identifier, uId: Identifier, vId: Identifier): RenderType = RenderType.create(
        "dream-displays-yuv",
        RenderSetup.builder(pipeline)
            .withTexture(SAMPLER_Y, yId) { planeSampler() }
            .withTexture(SAMPLER_U, uId) { planeSampler() }
            .withTexture(SAMPLER_V, vId) { planeSampler() }
            .createRenderSetup(),
    )

    /** Shared 1x1 white texture used for the loading / error quads in YUV mode. */
    private var whiteTextureId: Identifier? = null

    /**
     * A plain unlit [RenderType] over a shared 1x1 white texture, used for the loading /
     * error quads in YUV mode (the YUV pipeline would misinterpret a flat color as chroma).
     * Must be called on the render thread.
     */
    fun createFallback(): RenderType {
        val id = whiteTextureId ?: run {
            val img = NativeImage(NativeImage.Format.RGBA, 1, 1, false)
            img.setPixel(0, 0, -1)
            val tex = DynamicTexture({ "dreamdisplays:white" }, img)
            tex.upload()
            val newId = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "screen-white")
            Minecraft.getInstance().textureManager.register(newId, tex)
            whiteTextureId = newId
            newId
        }
        return DisplayUnlitRenderTypes.create("dream-displays-fallback", id)
    }

    /** Shared flat solid-color render type (a 1 x 1 white texture modulated by the vertex color). */
    @Volatile
    private var sharedSolidType: RenderType? = null

    /**
     * Shared flat solid-color render type (a 1 x 1 white texture modulated by the vertex color), used
     * to draw UI overlays such as the loading bar independently of any per-display video allocation.
     * Built once and reused. Render thread only.
     */
    fun solidColorType(): RenderType =
        sharedSolidType ?: createFallback().also { sharedSolidType = it }
    //?} else
    /*val active: Boolean get() = false

    private var whiteTextureId: Identifier? = null

    fun createFallback(): RenderType {
        val id = whiteTextureId ?: run {
            val img = NativeImage(NativeImage.Format.RGBA, 1, 1, false)
            img.setPixelRGBA(0, 0, -1)
            val tex = DynamicTexture(img)
            tex.upload()
            val newId = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "screen-white")
            Minecraft.getInstance().textureManager.register(newId, tex)
            whiteTextureId = newId
            newId
        }
        return DisplayUnlitRenderTypes.create("dream-displays-fallback", id)
    }

    @Volatile
    private var sharedSolidType: RenderType? = null

    fun solidColorType(): RenderType =
        sharedSolidType ?: createFallback().also { sharedSolidType = it }*/
}
