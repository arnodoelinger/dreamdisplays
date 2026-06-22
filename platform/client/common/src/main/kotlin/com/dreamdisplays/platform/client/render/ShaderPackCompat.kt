package com.dreamdisplays.platform.client.render

/**
 * Shader-pack detector. The mod must not depend on Iris / Oculus / OptiFine / Canvas,
 * but the renderer needs to avoid custom video fragment shaders while another renderer owns the
 * world pass.
 *
 * The only thing that we can accept from shaders is fog (it's clear why).
 */
internal object ShaderPackCompat {
    /** True when any supported shader pack is currently in use. */
    val isShaderPackActive: Boolean; get() = shaderBackendName() != "none"

    /** Name of the active shader backend (`iris` / `optifine` / `canvas`), or `none`. */
    fun shaderBackendName(): String = when {
        irisShaderPackActive() -> "iris"
        optifineShaderPackActive() -> "optifine"
        canvasRendererActive() -> "canvas"
        else -> "none"
    }

    /** Iris shaders. */
    private fun irisShaderPackActive(): Boolean = runCatching {
        val apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
        val api = apiClass.getMethod("getInstance").invoke(null)
        api.javaClass.getMethod("isShaderPackInUse").invoke(api) as? Boolean == true
    }.getOrDefault(false)

    /** Optifine shaders. */
    private fun optifineShaderPackActive(): Boolean = runCatching {
        Class.forName("net.optifine.Config").getMethod("isShaders").invoke(null) as? Boolean == true
    }.getOrDefault(false)

    /** Canvas shaders (it's an old project, but it's still in use by some people). */
    private fun canvasRendererActive(): Boolean =
        classPresent("grondag.canvas.CanvasMod") || classPresent("io.vram.canvas.CanvasFabricMod")

    /** True if the given class is present. */
    private fun classPresent(name: String): Boolean = runCatching {
        Class.forName(name, false, ShaderPackCompat::class.java.classLoader)
        true
    }.getOrDefault(false)
}
