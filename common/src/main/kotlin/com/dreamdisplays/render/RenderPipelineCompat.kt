package com.dreamdisplays.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.resources.Identifier

internal object RenderPipelineCompat {
    fun createDisplayPipeline(
        location: Identifier,
        vertexShader: Identifier,
        fragmentShader: Identifier,
        samplers: List<String>,
    ): RenderPipeline {
        val builder = RenderPipeline.builder()
            .withLocation(location)
            .withVertexShader(vertexShader)
            .withFragmentShader(fragmentShader)
            .withCull(false)

        configureDepth(builder)
        if (supportsBindGroupLayouts()) {
            configure262(builder, samplers)
        } else {
            configureLegacy(builder, samplers)
        }

        return builder.build()
    }

    fun configureDepth(builder: RenderPipeline.Builder) {
        val builderClass = builder.javaClass
        val depthStencilStateClass = runCatching {
            Class.forName("com.mojang.blaze3d.pipeline.DepthStencilState")
        }.getOrNull()
        if (depthStencilStateClass != null) {
            val defaultDepth = depthStencilStateClass.getField("DEFAULT").get(null)
            builderClass.getMethod("withDepthStencilState", depthStencilStateClass).invoke(builder, defaultDepth)
            return
        }

        val depthTestFunctionClass = runCatching {
            Class.forName("com.mojang.blaze3d.platform.DepthTestFunction")
        }.getOrNull() ?: return
        @Suppress("UNCHECKED_CAST")
        val lequalDepth = java.lang.Enum.valueOf(depthTestFunctionClass as Class<out Enum<*>>, "LEQUAL_DEPTH_TEST")
        builderClass.getMethod("withDepthTestFunction", depthTestFunctionClass).invoke(builder, lequalDepth)
        runCatching {
            builderClass.getMethod("withDepthWrite", Boolean::class.javaPrimitiveType).invoke(builder, true)
        }
    }

    private fun supportsBindGroupLayouts(): Boolean =
        runCatching { Class.forName("com.mojang.blaze3d.pipeline.BindGroupLayout") }.isSuccess

    private fun configureLegacy(builder: RenderPipeline.Builder, samplers: List<String>) {
        val builderClass = builder.javaClass
        val withUniform = builderClass.getMethod("withUniform", String::class.java, UniformType::class.java)
        withUniform.invoke(builder, "DynamicTransforms", UniformType.UNIFORM_BUFFER)
        withUniform.invoke(builder, "Projection", UniformType.UNIFORM_BUFFER)
        withUniform.invoke(builder, "Fog", UniformType.UNIFORM_BUFFER)

        val withSampler = builderClass.getMethod("withSampler", String::class.java)
        for (sampler in samplers) {
            withSampler.invoke(builder, sampler)
        }

        //? if >=26 {
        val modeClass = Class.forName("com.mojang.blaze3d.vertex.VertexFormat\$Mode")
        @Suppress("UNCHECKED_CAST")
        val quads = java.lang.Enum.valueOf(modeClass as Class<out Enum<*>>, "QUADS")
        builderClass.getMethod("withVertexFormat", VertexFormat::class.java, modeClass)
            .invoke(builder, DefaultVertexFormat.POSITION_TEX_COLOR, quads)
        //?} else
        /*val modeClass = VertexFormat.Mode::class.java
        val quads = VertexFormat.Mode.QUADS
        builderClass.getMethod("withVertexFormat", VertexFormat::class.java, modeClass)
            .invoke(builder, DefaultVertexFormat.POSITION_TEX_COLOR, quads)*/
    }

    private fun configure262(builder: RenderPipeline.Builder, samplers: List<String>) {
        val builderClass = builder.javaClass
        val bglClass = Class.forName("com.mojang.blaze3d.pipeline.BindGroupLayout")
        val withBindGroupLayout = builderClass.getMethod("withBindGroupLayout", bglClass)

        withBindGroupLayout.invoke(builder, vanillaLayout("GLOBALS"))
        withBindGroupLayout.invoke(builder, vanillaLayout("MATRICES_PROJECTION"))
        withBindGroupLayout.invoke(builder, vanillaLayout("FOG"))
        withBindGroupLayout.invoke(
            builder,
            if (samplers == listOf("Sampler0")) vanillaLayout("SAMPLER0") else samplerLayout(samplers),
        )

        builderClass.getMethod("withVertexBinding", Int::class.javaPrimitiveType, VertexFormat::class.java)
            .invoke(builder, 0, DefaultVertexFormat.POSITION_TEX_COLOR)

        val topologyClass = Class.forName("com.mojang.blaze3d.PrimitiveTopology")
        @Suppress("UNCHECKED_CAST")
        val quads = java.lang.Enum.valueOf(topologyClass as Class<out Enum<*>>, "QUADS")
        builderClass.getMethod("withPrimitiveTopology", topologyClass).invoke(builder, quads)
    }

    private fun samplerLayout(samplers: List<String>): Any {
        val bglClass = Class.forName("com.mojang.blaze3d.pipeline.BindGroupLayout")
        val layoutBuilder = bglClass.getMethod("builder").invoke(null)
        val withSampler = layoutBuilder.javaClass.getMethod("withSampler", String::class.java)
        for (sampler in samplers) {
            withSampler.invoke(layoutBuilder, sampler)
        }
        return layoutBuilder.javaClass.getMethod("build").invoke(layoutBuilder)
    }

    private fun vanillaLayout(name: String): Any =
        Class.forName("net.minecraft.client.renderer.BindGroupLayouts").getField(name).get(null)
}
