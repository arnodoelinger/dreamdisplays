//? if >=1.21.11 {
package com.dreamdisplays.platform.client.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.resources.Identifier

internal object RenderPipelineCompat {
    /** Creates a render pipeline for a display quad. */
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
        configureBlend(builder)
        if (supportsBindGroupLayouts()) {
            configure262(builder, samplers)
        } else {
            configureLegacy(builder, samplers)
        }

        return builder.build()
    }

    fun configureBlend(builder: RenderPipeline.Builder) {
        val builderClass = builder.javaClass
        val blendFunctionClass = runCatching {
            Class.forName("com.mojang.blaze3d.pipeline.BlendFunction")
        }.getOrNull() ?: return
        val translucent = blendFunctionClass.getField("TRANSLUCENT").get(null)

        val colorTargetStateClass = runCatching {
            Class.forName("com.mojang.blaze3d.pipeline.ColorTargetState")
        }.getOrNull()
        if (colorTargetStateClass != null) {
            val state = colorTargetStateClass.getConstructor(blendFunctionClass).newInstance(translucent)
            builderClass.getMethod("withColorTargetState", colorTargetStateClass).invoke(builder, state)
            return
        }

        runCatching {
            builderClass.getMethod("withBlend", blendFunctionClass).invoke(builder, translucent)
        }
    }

    /**
     * Constant term of the GPU polygon-offset bias applied to display quads (see [configureDepth]).
     * Matches vanilla's own depth-bias constant.
     */
    private const val DEPTH_BIAS = 3.0f

    /** Configures the depth state of the pipeline. */
    fun configureDepth(builder: RenderPipeline.Builder) {
        val builderClass = builder.javaClass
        val depthStencilStateClass = runCatching {
            Class.forName("com.mojang.blaze3d.pipeline.DepthStencilState")
        }.getOrNull()
        if (depthStencilStateClass != null) {
            val defaultDepth = depthStencilStateClass.getField("DEFAULT").get(null)
            val biasedDepth = withPolygonOffset(depthStencilStateClass, defaultDepth)
            builderClass.getMethod("withDepthStencilState", depthStencilStateClass).invoke(builder, biasedDepth)
            return
        }

        val depthTestFunctionClass = runCatching {
            Class.forName("com.mojang.blaze3d.platform.DepthTestFunction")
        }.getOrNull() ?: return

        val lequalDepth = reflectiveEnumValue(depthTestFunctionClass, "LEQUAL_DEPTH_TEST")
        builderClass.getMethod("withDepthTestFunction", depthTestFunctionClass).invoke(builder, lequalDepth)
        runCatching {
            builderClass.getMethod("withDepthWrite", Boolean::class.javaPrimitiveType).invoke(builder, true)
        }
    }

    /**
     * Rebuilds [defaultDepth] with a small GPU polygon-offset bias (`depthBiasScaleFactor` /
     * `depthBiasConstant`) so the display quad wins the depth test against the block face directly
     * behind it.
     */
    private fun withPolygonOffset(depthStencilStateClass: Class<*>, defaultDepth: Any): Any {
        val depthTest = depthStencilStateClass.getMethod("depthTest").invoke(defaultDepth)
        val writeDepth = depthStencilStateClass.getMethod("writeDepth").invoke(defaultDepth) as Boolean
        val reversedZ = (depthTest as Enum<*>).name == "GREATER_THAN_OR_EQUAL"
        val bias = if (reversedZ) DEPTH_BIAS else -DEPTH_BIAS

        val compareOpClass = Class.forName("com.mojang.blaze3d.platform.CompareOp")
        val ctor = depthStencilStateClass.getConstructor(
            compareOpClass,
            Boolean::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        )
        return ctor.newInstance(depthTest, writeDepth, bias, bias)
    }

    /** True if the current version of Minecraft supports `BindGroupLayout`s. */
    private fun supportsBindGroupLayouts(): Boolean =
        runCatching { Class.forName("com.mojang.blaze3d.pipeline.BindGroupLayout") }.isSuccess

    /** Configures the pipeline for a display quad. */
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
        val modeClass = Class.forName($$"com.mojang.blaze3d.vertex.VertexFormat$Mode")

        val quads = reflectiveEnumValue(modeClass, "QUADS")
        builderClass.getMethod("withVertexFormat", VertexFormat::class.java, modeClass)
            .invoke(builder, DefaultVertexFormat.POSITION_TEX_COLOR, quads)
        //?} else
        /*val modeClass = VertexFormat.Mode::class.java
        val quads = VertexFormat.Mode.QUADS
        builderClass.getMethod("withVertexFormat", VertexFormat::class.java, modeClass)
            .invoke(builder, DefaultVertexFormat.POSITION_TEX_COLOR, quads)*/
    }

    /** Configures the pipeline for a display quad. */
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

        val quads = reflectiveEnumValue(topologyClass, "QUADS")
        builderClass.getMethod("withPrimitiveTopology", topologyClass).invoke(builder, quads)
    }

    /** Creates a `BindGroupLayout` for the given samplers. */
    private fun samplerLayout(samplers: List<String>): Any {
        val bglClass = Class.forName("com.mojang.blaze3d.pipeline.BindGroupLayout")
        val layoutBuilder = bglClass.getMethod("builder").invoke(null)
        val withSampler = layoutBuilder.javaClass.getMethod("withSampler", String::class.java)
        for (sampler in samplers) {
            withSampler.invoke(layoutBuilder, sampler)
        }
        return layoutBuilder.javaClass.getMethod("build").invoke(layoutBuilder)
    }

    /** Gets the `BindGroupLayout` for the given name. */
    private fun vanillaLayout(name: String): Any =
        Class.forName("net.minecraft.client.renderer.BindGroupLayouts").getField(name).get(null)

    /** Resolves enum constant [name] on [enumClass], whose static type is not known at compile time. */
    fun reflectiveEnumValue(enumClass: Class<*>, name: String): Any =
        enumClass.enumConstants.first { (it as Enum<*>).name == name }
}
//?}
