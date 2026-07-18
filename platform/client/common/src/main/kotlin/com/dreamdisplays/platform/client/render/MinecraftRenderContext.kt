package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.render.RenderContext
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera

/**
 * Minecraft-backed [RenderContext]. The platform adapter that lets the platform-agnostic
 * [ClientRenderService] contract drive the existing world renderer:
 * the contract's `renderAll(RenderContext)` receives this, casts it back, and reaches the live
 * [PoseStack] and [Camera].
 *
 * [cameraX]/[cameraY]/[cameraZ] are derived straight from [camera] so the contract surface stays
 * Minecraft-free while the concrete render path keeps the rich types it needs.
 */
class MinecraftRenderContext(
    /** The live pose stack for the current frame. */
    val stack: PoseStack,
    /** The active world camera. */
    val camera: Camera,
    /** Fraction of a tick elapsed since the last full tick. */
    override val tickDelta: Float,
) : RenderContext {
    /** Camera world X. */
    override val cameraX: Double get() = cameraPosition().x

    /** Camera world Y. */
    override val cameraY: Double get() = cameraPosition().y

    /** Camera world Z. */
    override val cameraZ: Double get() = cameraPosition().z

    private fun cameraPosition() =
        //? if >=1.21.11 {
        camera.position()
    //?} else
    /*camera.getPosition()*/
}
