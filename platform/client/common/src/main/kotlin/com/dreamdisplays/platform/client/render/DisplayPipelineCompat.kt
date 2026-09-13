//? if >=1.21.11 {
package com.dreamdisplays.platform.client.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline

/**
 * Applies the display pipelines' translucent blend and default depth state across Minecraft versions.
 * 26.x exposes typed builder methods; older versions fall back to reflection.
 */
internal fun RenderPipeline.Builder.withDisplayColorAndDepth(): RenderPipeline.Builder {
    val builderClass = javaClass
    val colorTargetState = runCatching { Class.forName("com.mojang.blaze3d.pipeline.ColorTargetState") }.getOrNull()
    if (colorTargetState != null) {
        val state = colorTargetState.getConstructor(BlendFunction::class.java).newInstance(BlendFunction.TRANSLUCENT)
        builderClass.getMethod("withColorTargetState", colorTargetState).invoke(this, state)
        val depthStencilState = Class.forName("com.mojang.blaze3d.pipeline.DepthStencilState")
        builderClass.getMethod("withDepthStencilState", depthStencilState)
            .invoke(this, depthStencilState.getField("DEFAULT").get(null))
    } else {
        builderClass.getMethod("withBlend", BlendFunction::class.java).invoke(this, BlendFunction.TRANSLUCENT)
    }
    return this
}
//?}
