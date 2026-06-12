package com.dreamdisplays.render

import com.dreamdisplays.Initializer
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.Identifier

/**
 * Reflective GPU-YUV backend for the 26.2+ Blaze3D API. This module compiles against the
 * 26.1-era pipeline builder (`withSampler` / `TextureFormat`), which no longer exists at 26.2
 * runtime; this object rebuilds the same pipeline through reflection over the new
 * `BindGroupLayout` / `GpuFormat` API instead, so one binary serves every loader and version.
 *
 * Reflection only runs once per session (pipeline and texture-method handles are resolved on
 * first use); the per-frame hot path is untouched. The layout chain mirrors vanilla's BLOCK
 * snippet (GLOBALS, FOG, samplers, MATRICES_PROJECTION) with the sampler group widened to the
 * three video planes plus the lightmap.
 */
internal object Yuv262Reflect {
    /** True when the 26.2+ API classes are present at runtime. */
    val isAvailable: Boolean by lazy {
        runCatching {
            Class.forName("com.mojang.blaze3d.pipeline.BindGroupLayout")
            Class.forName("com.mojang.blaze3d.GpuFormat")
        }.isSuccess
    }

    private val bglClass: Class<*> by lazy { Class.forName("com.mojang.blaze3d.pipeline.BindGroupLayout") }
    private val gpuFormatClass: Class<*> by lazy { Class.forName("com.mojang.blaze3d.GpuFormat") }
    private val gpuFormatR8: Any by lazy {
        @Suppress("UNCHECKED_CAST")
        java.lang.Enum.valueOf(gpuFormatClass as Class<out Enum<*>>, "R8_UNORM")
    }

    /** Looks up a vanilla [net.minecraft.client.renderer.BindGroupLayouts] constant. */
    private fun vanillaLayout(name: String): Any =
        Class.forName("net.minecraft.client.renderer.BindGroupLayouts").getField(name).get(null)

    /** Builds the BindGroupLayout holding the three plane samplers plus the lightmap. */
    private fun samplerLayout(): Any {
        val builder = bglClass.getMethod("builder").invoke(null)
        val withSampler = builder.javaClass.getMethod("withSampler", String::class.java)
        for (name in listOf("Sampler0", "Sampler1", "Sampler2", "Sampler3")) {
            withSampler.invoke(builder, name)
        }
        return builder.javaClass.getMethod("build").invoke(builder)
    }

    /**
     * Builds the YUV [RenderPipeline] with the 26.2 builder API. `builder()`, `withLocation`,
     * `withVertexShader`, `withFragmentShader`, and `build()` are binary-stable and called
     * directly; everything 26.2-specific goes through reflection.
     */
    fun createPipeline(): RenderPipeline {
        val builder = RenderPipeline.builder()
        val builderClass = builder.javaClass

        val withBindGroupLayout = builderClass.getMethod("withBindGroupLayout", bglClass)
        withBindGroupLayout.invoke(builder, vanillaLayout("GLOBALS"))
        withBindGroupLayout.invoke(builder, vanillaLayout("FOG"))
        withBindGroupLayout.invoke(builder, samplerLayout())
        withBindGroupLayout.invoke(builder, vanillaLayout("MATRICES_PROJECTION"))

        builderClass.getMethod("withVertexBinding", Int::class.javaPrimitiveType, VertexFormat::class.java)
            .invoke(builder, 0, DefaultVertexFormat.BLOCK)

        val topologyClass = Class.forName("com.mojang.blaze3d.PrimitiveTopology")
        @Suppress("UNCHECKED_CAST")
        val quads = java.lang.Enum.valueOf(topologyClass as Class<out Enum<*>>, "QUADS")
        builderClass.getMethod("withPrimitiveTopology", topologyClass).invoke(builder, quads)

        val depthStateClass = Class.forName("com.mojang.blaze3d.pipeline.DepthStencilState")
        builderClass.getMethod("withDepthStencilState", depthStateClass)
            .invoke(builder, depthStateClass.getField("DEFAULT").get(null))

        builder.withLocation(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "pipeline/display_yuv"))
        builder.withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/block"))
        builder.withFragmentShader(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "core/display_yuv"))
        return builder.build()
    }

    /**
     * Creates one R8_UNORM plane texture through the 26.2 `createTexture(String, int, GpuFormat,
     * ...)` overload. View and sampler creation are binary-stable and called directly.
     */
    fun createPlaneTexture(label: String, width: Int, height: Int): AbstractTexture =
        object : AbstractTexture() {
            init {
                val device = RenderSystem.getDevice()
                val createTexture = device.javaClass.getMethod(
                    "createTexture",
                    String::class.java, Int::class.javaPrimitiveType, gpuFormatClass,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                )
                texture = createTexture.invoke(
                    device, label,
                    GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST,
                    gpuFormatR8, width, height, 1, 1,
                ) as GpuTexture
                textureView = device.createTextureView(texture!!) as GpuTextureView
                sampler = DisplayYuvRenderTypes.planeSampler()
            }
        }
}
