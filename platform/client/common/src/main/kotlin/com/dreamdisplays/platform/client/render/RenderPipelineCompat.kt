//? if >=1.21.11 {
package com.dreamdisplays.platform.client.render

//? if <26.3 {
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.VertexFormat
//?}
import com.mojang.blaze3d.vertex.DefaultVertexFormat
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

    fun configureBlend(builder: RenderPipelineBuilder) {
        val builderClass = builder.javaClass
        val blendFunctionClass = loadClass(
            "com.mojang.renderpearl.api.pipeline.BlendFunction",
            "com.mojang.blaze3d.pipeline.BlendFunction",
        ) ?: return
        val translucent = blendFunctionClass.getField("TRANSLUCENT").get(null)

        val colorTargetStateClass = loadClass(
            "com.mojang.renderpearl.api.pipeline.ColorTargetState",
            "com.mojang.blaze3d.pipeline.ColorTargetState",
        )
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
    fun configureDepth(builder: RenderPipelineBuilder) {
        val builderClass = builder.javaClass
        val depthStencilStateClass = loadClass(
            "com.mojang.renderpearl.api.pipeline.DepthStencilState",
            "com.mojang.blaze3d.pipeline.DepthStencilState",
        )
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

        val compareOpClass = loadClass(
            "com.mojang.renderpearl.api.pipeline.CompareOp",
            "com.mojang.blaze3d.platform.CompareOp",
        ) ?: return defaultDepth
        val ctor = depthStencilStateClass.getConstructor(
            compareOpClass,
            Boolean::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        )
        return ctor.newInstance(depthTest, writeDepth, bias, bias)
    }

    /** True if the current version of Minecraft supports `BindGroupLayout`s. */
    private fun supportsBindGroupLayouts(): Boolean = bindGroupLayoutClass() != null

    private fun bindGroupLayoutClass(): Class<*>? = loadClass(
        "com.mojang.renderpearl.api.pipeline.BindGroupLayout",
        "com.mojang.blaze3d.pipeline.BindGroupLayout",
    )

    private fun loadClass(vararg names: String): Class<*>? =
        names.firstNotNullOfOrNull { runCatching { Class.forName(it) }.getOrNull() }

    /** Configures the pipeline for a display quad. */
    private fun configureLegacy(builder: RenderPipelineBuilder, samplers: List<String>) {
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
    private fun configure262(builder: RenderPipelineBuilder, samplers: List<String>) {
        val builderClass = builder.javaClass
        val bglClass = bindGroupLayoutClass() ?: return
        val withBindGroupLayout = builderClass.getMethod("withBindGroupLayout", bglClass)

        bindWorldDisplayUniforms(withBindGroupLayout, builder)
        withBindGroupLayout.invoke(
            builder,
            if (samplers == listOf("Sampler0")) vanillaLayout("SAMPLER0") else samplerLayout(samplers),
        )

        builderClass.getMethod("withVertexBinding", Int::class.javaPrimitiveType, VertexFormat::class.java)
            .invoke(builder, 0, DefaultVertexFormat.POSITION_TEX_COLOR)

        val topologyClass = loadClass(
            "com.mojang.renderpearl.api.pipeline.PrimitiveTopology",
            "com.mojang.blaze3d.PrimitiveTopology",
        ) ?: return

        val quads = reflectiveEnumValue(topologyClass, "QUADS")
        builderClass.getMethod("withPrimitiveTopology", topologyClass).invoke(builder, quads)
    }

    internal fun bindWorldDisplayUniforms(withBindGroupLayout: java.lang.reflect.Method, builder: Any) {
        withBindGroupLayout.invoke(builder, vanillaLayout("GLOBALS"))
        if (hasLayout("DYNAMIC_TRANSFORMS")) {
            withBindGroupLayout.invoke(builder, vanillaLayout("PROJECTION"))
            withBindGroupLayout.invoke(builder, vanillaLayout("DYNAMIC_TRANSFORMS"))
        } else {
            withBindGroupLayout.invoke(builder, vanillaLayout("MATRICES_PROJECTION"))
        }
        withBindGroupLayout.invoke(builder, vanillaLayout("FOG"))
    }

    private fun hasLayout(name: String): Boolean =
        runCatching {
            Class.forName("net.minecraft.client.renderer.BindGroupLayouts").getField(name)
        }.isSuccess

    /** Creates a `BindGroupLayout` for the given samplers. */
    private fun samplerLayout(samplers: List<String>): Any {
        val bglClass = bindGroupLayoutClass() ?: error("BindGroupLayout is missing")
        val layoutBuilder = bglClass.getMethod("builder").invoke(null)
        val withSampler = runCatching {
            layoutBuilder.javaClass.getMethod("withSampler", String::class.java)
        }.getOrNull()
        if (withSampler != null) {
            for (sampler in samplers) {
                withSampler.invoke(layoutBuilder, sampler)
            }
        } else {
            val uniformTypeClass = loadClass(
                "com.mojang.renderpearl.api.pipeline.UniformType",
                "com.mojang.blaze3d.shaders.UniformType",
            ) ?: error("UniformType is missing")
            val combined = reflectiveEnumValue(uniformTypeClass, "COMBINED_IMAGE_SAMPLER")
            val withUniform = layoutBuilder.javaClass.getMethod(
                "withUniform",
                String::class.java,
                uniformTypeClass,
            )
            for (sampler in samplers) {
                withUniform.invoke(layoutBuilder, sampler, combined)
            }
        }
        return layoutBuilder.javaClass.getMethod("build").invoke(layoutBuilder)
    }

    /** Gets the `BindGroupLayout` for the given name. */
    private fun vanillaLayout(vararg names: String): Any {
        val clazz = Class.forName("net.minecraft.client.renderer.BindGroupLayouts")
        val last = names.last()
        names.forEach { name ->
            runCatching { return clazz.getField(name).get(null) }
        }
        return clazz.getField(last).get(null)
    }

    /** Resolves enum constant [name] on [enumClass], whose static type is not known at compile time. */
    fun reflectiveEnumValue(enumClass: Class<*>, name: String): Any =
        enumClass.enumConstants.first { (it as Enum<*>).name == name }
}
//?}
