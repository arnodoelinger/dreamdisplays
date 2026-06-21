package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/**
 * GPU-side YUV -> RGB path: a custom [RenderPipeline] that samples the three I420 planes
 * (Y / U / V as RED8 textures) and converts them to RGB in the fragment shader, plus the
 * [RenderType] factories that bind a display's plane textures to it.
 *
 * The vertex stage uses the mod's unlit `core/display_fog` (it emits the spherical/cylindrical
 * vertex distances so the fragment shader can apply vanilla distance fog without any lightmap or
 * normals); the fragment shader is `assets/dreamdisplays/shaders/core/display_yuv.fsh`. Brightness
 * rides in on the vertex color, scaled by 0.5 so the 0..2 range fits a normalized byte (the shader
 * multiplies by 2).
 */
object DisplayYuvRenderTypes {
    private var sharedPlaneSampler: com.mojang.blaze3d.textures.GpuSampler? = null

    /**
     * Lazily creates the shared linear/clamp sampler used by all video planes. The device does
     * not cache samplers, so one instance is shared and intentionally never closed. The
     * `createSampler` signature is binary-stable across 26.1 and 26.2, so this lives here
     * rather than in the version-specific texture classes.
     */
    fun planeSampler(): com.mojang.blaze3d.textures.GpuSampler =
        sharedPlaneSampler ?: com.mojang.blaze3d.systems.RenderSystem.getDevice().createSampler(
            com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
            com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
            com.mojang.blaze3d.textures.FilterMode.LINEAR,
            com.mojang.blaze3d.textures.FilterMode.LINEAR,
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
     * Single decision point for the GPU-YUV mode: the native library must produce planar
     * frames and the runtime must expose a usable pipeline API (built-in 26.1-era, or 26.2+
     * via [Yuv262Reflect]). Both the texture allocation and the frame pipe read this so they
     * can never disagree.
     */
    val active: Boolean
        get() = !ShaderPackCompat.isShaderPackActive
                && (isSupported || Yuv262Reflect.isAvailable)
                && NativeMedia.yuvGpuEnabled

    /** Creates one RED8 plane texture through the built-in (26.1-era) or reflective 26.2 API. */
    fun createPlaneTexture(label: String, width: Int, height: Int): net.minecraft.client.renderer.texture.AbstractTexture =
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

    @Volatile private var sharedSolidType: RenderType? = null

    /**
     * Shared flat solid-color render type (a 1 x 1 white texture modulated by the vertex color), used
     * to draw UI overlays such as the loading bar independently of any per-display video allocation.
     * Built once and reused. Render thread only.
     */
    fun solidColorType(): RenderType =
        sharedSolidType ?: createFallback().also { sharedSolidType = it }
}
