package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.Initializer
import com.mojang.blaze3d.pipeline.RenderPipeline
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
}
