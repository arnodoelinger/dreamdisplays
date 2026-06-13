package com.dreamdisplays.render

import com.dreamdisplays.Initializer
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier

/**
 * Shared unlit world render types for display quads.
 *
 * The display must keep normal depth testing, but it should not go through vanilla block / entity
 * lighting pipelines: shader packs commonly replace those and end up shading the video.
 */
object DisplayUnlitRenderTypes {
    private const val SAMPLER_TEXTURE = "Sampler0"

    private val supportsLegacyBuilderApi: Boolean by lazy {
        runCatching {
            RenderPipeline.Builder::class.java.getMethod("withSampler", String::class.java)
            true
        }.getOrDefault(false)
    }

    private val texturedPipeline: RenderPipeline by lazy {
        val pipeline = if (supportsLegacyBuilderApi) createLegacyTexturedPipeline() else create262TexturedPipeline()
        assignIrisTexturedProgram(pipeline)
        pipeline
    }

    fun create(name: String, id: Identifier): RenderType = RenderType.create(
        name,
        RenderSetup.builder(texturedPipeline)
            .withTexture(SAMPLER_TEXTURE, id)
            .createRenderSetup(),
    )

    private fun createLegacyTexturedPipeline(): RenderPipeline = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "pipeline/display_unlit_textured"))
        // Custom unlit vertex / fragment that still applies vanilla distance fog (display_fog),
        // so the display fades with fog/render distance without going through block/entity lighting.
        .withVertexShader(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "core/display_fog"))
        .withFragmentShader(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "core/display_fog"))
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withUniform("Fog", UniformType.UNIFORM_BUFFER)
        .withSampler(SAMPLER_TEXTURE)
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withCull(false)
        .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
        .build()

    private fun create262TexturedPipeline(): RenderPipeline {
        val builder = RenderPipeline.builder()
        val builderClass = builder.javaClass
        val bglClass = Class.forName("com.mojang.blaze3d.pipeline.BindGroupLayout")
        val withBindGroupLayout = builderClass.getMethod("withBindGroupLayout", bglClass)

        withBindGroupLayout.invoke(builder, vanillaLayout("GLOBALS"))
        withBindGroupLayout.invoke(builder, vanillaLayout("MATRICES_PROJECTION"))
        withBindGroupLayout.invoke(builder, vanillaLayout("FOG"))
        withBindGroupLayout.invoke(builder, vanillaLayout("SAMPLER0"))

        builderClass.getMethod("withVertexBinding", Int::class.javaPrimitiveType, VertexFormat::class.java)
            .invoke(builder, 0, DefaultVertexFormat.POSITION_TEX_COLOR)

        val topologyClass = Class.forName("com.mojang.blaze3d.PrimitiveTopology")
        @Suppress("UNCHECKED_CAST")
        val quads = java.lang.Enum.valueOf(topologyClass as Class<out Enum<*>>, "QUADS")
        builderClass.getMethod("withPrimitiveTopology", topologyClass).invoke(builder, quads)

        builder.withLocation(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "pipeline/display_unlit_textured"))
        builder.withVertexShader(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "core/display_fog"))
        builder.withFragmentShader(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "core/display_fog"))
        builder.withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        builder.withDepthStencilState(DepthStencilState.DEFAULT)
        builder.withCull(false)
        return builder.build()
    }

    private fun vanillaLayout(name: String): Any =
        Class.forName("net.minecraft.client.renderer.BindGroupLayouts").getField(name).get(null)

    private fun assignIrisTexturedProgram(pipeline: RenderPipeline) {
        runCatching {
            val apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
            val programClass = Class.forName("net.irisshaders.iris.api.v0.IrisProgram")
            val api = apiClass.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val textured = java.lang.Enum.valueOf(programClass as Class<out Enum<*>>, "TEXTURED")
            apiClass.getMethod("assignPipeline", RenderPipeline::class.java, programClass).invoke(api, pipeline, textured)
        }
    }
}
