package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.Initializer
//? if >=1.21.11 {
import com.mojang.blaze3d.pipeline.RenderPipeline
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
//?} else
/*import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation as Identifier*/

/**
 * Shared unlit world render types for display quads.
 *
 * The display must keep normal depth testing, but it should not go through vanilla block / entity
 * lighting pipelines: shader packs commonly replace those and end up shading the video.
 */
object DisplayUnlitRenderTypes {
    //? if >=1.21.11 {
    /** Name of the texture sampler uniform in the display shader. */
    private const val SAMPLER_TEXTURE = "Sampler0"

    /** Lazily-built unlit textured pipeline for display quads. */
    private val texturedPipeline: RenderPipeline by lazy {
        val pipeline = RenderPipelineCompat.createDisplayPipeline(
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "pipeline/display_unlit_textured"),
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "core/display_fog"),
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "core/display_fog"),
            listOf(SAMPLER_TEXTURE),
        )
        assignIrisTexturedProgram(pipeline)
        pipeline
    }

    /** Creates an unlit [RenderType] named [name] that samples texture [id]. */
    fun create(name: String, id: Identifier): RenderType = RenderType.create(
        name,
        RenderSetup.builder(texturedPipeline)
            .withTexture(SAMPLER_TEXTURE, id)
            .createRenderSetup(),
    )

    /** Registers [pipeline] with Iris's `TEXTURED` program so shader packs treat it correctly; no-op without Iris. */
    private fun assignIrisTexturedProgram(pipeline: RenderPipeline) {
        runCatching {
            val apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
            val programClass = Class.forName("net.irisshaders.iris.api.v0.IrisProgram")
            val api = apiClass.getMethod("getInstance").invoke(null)

            @Suppress("UNCHECKED_CAST")
            val textured = java.lang.Enum.valueOf(programClass as Class<out Enum<*>>, "TEXTURED")
            apiClass.getMethod("assignPipeline", RenderPipeline::class.java, programClass)
                .invoke(api, pipeline, textured)
        }
    }
    //?} else
    /*fun create(name: String, id: Identifier): RenderType {
        val state = RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard(java.util.function.Supplier {
                GameRenderer.getPositionTexColorShader()
            }))
            .setTextureState(RenderStateShard.TextureStateShard(id, false, false))
            .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
            .createCompositeState(false)
        return RenderType::class.java.getDeclaredMethod(
            "create",
            String::class.java,
            VertexFormat::class.java,
            VertexFormat.Mode::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            state.javaClass,
        ).apply { isAccessible = true }.invoke(
            null,
            name,
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            false,
            state,
        ) as RenderType
    }*/
}
