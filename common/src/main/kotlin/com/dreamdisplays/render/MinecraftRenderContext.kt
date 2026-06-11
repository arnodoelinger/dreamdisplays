package com.dreamdisplays.render

import com.dreamdisplays.render.api.RenderContext
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera

/**
 * Minecraft-backed [RenderContext]. The platform adapter that lets the platform-agnostic
 * [com.dreamdisplays.client.render.ClientRenderService] contract drive the existing world renderer:
 * the contract's `renderAll(RenderContext)` receives this, casts it back, and reaches the live
 * [PoseStack] and [Camera].
 *
 * [cameraX]/[cameraY]/[cameraZ] are derived straight from [camera] so the contract surface stays
 * Minecraft-free while the concrete render path keeps the rich types it needs.
 */
class MinecraftRenderContext(
    val stack: PoseStack,
    val camera: Camera,
    override val tickDelta: Float,
) : RenderContext {
    override val cameraX: Double get() = camera.position().x
    override val cameraY: Double get() = camera.position().y
    override val cameraZ: Double get() = camera.position().z
}
