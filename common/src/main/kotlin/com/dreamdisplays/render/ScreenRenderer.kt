package com.dreamdisplays.render

import com.dreamdisplays.api.DisplayId
import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.client.render.ClientRenderService
import com.dreamdisplays.client.render.DisplayRenderEntry
import com.dreamdisplays.client.render.RenderHook
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.displays.DisplayScreen
import com.dreamdisplays.render.api.RenderContext
import com.dreamdisplays.render.api.TextureHandle
import com.mojang.blaze3d.vertex.*
import net.minecraft.client.Camera
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.sin

/** Renders screens in the world. */
object ScreenRenderer : ClientRenderService {
    private typealias QuadAppender = (PoseStack.Pose, VertexConsumer) -> Unit
    private typealias QuadRenderer = (RenderType, QuadAppender) -> Unit

    /** Iterates all registered screens and renders each one relative to [camera]. */
    fun render(stack: PoseStack, camera: Camera) {
        render(stack, camera) { type, appendVertices ->
            drawImmediate(stack, type, appendVertices)
        }
    }

    /**
     * [ClientRenderService] render entry point. Unwraps a [MinecraftRenderContext] and delegates to
     * [render]; any other [RenderContext] type is a no-op (this renderer can only draw through a live
     * [PoseStack] / [Camera]).
     */
    override fun renderAll(context: RenderContext) {
        val ctx = context as? MinecraftRenderContext ?: return
        render(ctx.stack, ctx.camera)
    }

    /** No-op: live screens are created by [DisplayRegistry] from network packets, not flat entries. */
    override fun registerDisplay(entry: DisplayRenderEntry) = Unit

    /** Unregisters the live screen matching [displayId], delegating to [DisplayRegistry]. */
    override fun unregisterDisplay(displayId: DisplayId) {
        DisplayRegistry.getScreens()
            .firstOrNull { it.uuid == displayId.uuid }
            ?.let { DisplayRegistry.unregisterScreen(it) }
    }

    /** No-op: each [DisplayScreen] owns its own GPU texture lifecycle in the current model. */
    override fun updateTexture(displayId: DisplayId, handle: TextureHandle) = Unit

    /** Number of live screens with an uploaded texture. Those this renderer will actually draw. */
    override val registeredCount: Int
        get() = DisplayRegistry.getScreens().count { it.texture != null }

    /** Iterates all registered screens and lets the caller submit quads through the active renderer. */
    fun render(stack: PoseStack, camera: Camera, drawQuad: QuadRenderer) {
        val cameraPos = camera.position()
        for (displayScreen in DisplayRegistry.getScreens()) {
            if (displayScreen.texture == null) continue

            stack.pushPose()

            val pos = displayScreen.pos
            val screenCenter = Vec3.atLowerCornerOf(pos)
            val relativePos = screenCenter.subtract(cameraPos)
            stack.translate(relativePos.x, relativePos.y, relativePos.z)

            renderScreenTexture(displayScreen, stack, drawQuad)

            stack.popPose()
        }

        // The registered RenderHook extends the world pass after the mod's own screens
        // (by default it dispatches API-registered surfaces, see DreamServices.bootstrap).
        // The world render hooks do not surface a partial tick, hence the 0f tickDelta.
        DreamServices.registry.getOrNull<RenderHook>()
            ?.onRender(MinecraftRenderContext(stack, camera, 0f))
    }

    /** Translates and rotates the pose for [displayScreen]'s facing direction, then renders the video or fallback color. */
    private fun renderScreenTexture(displayScreen: DisplayScreen, stack: PoseStack, drawQuad: QuadRenderer) {
        // Upload the latest decoded frame to the GPU texture (if a new one is ready).
        // Done here on the render thread instead of via mc.execute() per frame.
        displayScreen.fitTexture()

        stack.pushPose()
        DisplayGeometry.applyScreenTransform(stack, displayScreen.facing, displayScreen.width, displayScreen.height)

        if (displayScreen.isVideoStarted && displayScreen.texture != null && displayScreen.renderType != null) {
            renderGpuTexture(drawQuad, displayScreen.renderType!!)
        } else if (displayScreen.renderType != null) {
            if (displayScreen.errored) {
                renderColor(drawQuad, displayScreen.renderType!!, 35, 5, 5)
            } else {
                val pulse = abs(sin(System.nanoTime() / 1_500_000_000.0 * Math.PI)).toFloat()
                val v = (10 + pulse * 20).toInt()
                renderColor(drawQuad, displayScreen.renderType!!, v, v, v)
            }
        }
        stack.popPose()
    }

    /** Draws a unit quad using the screen's GPU texture. */
    private fun renderGpuTexture(drawQuad: QuadRenderer, type: RenderType) {
        drawQuad(type) { pose, builder ->
            builder.addVertex(pose, 0f, 0f, 0f).setColor(255, 255, 255, 255).setUv(0f, 1f).setLight(0xF000F0)
                .setNormal(0f, 0f, 1f)
            builder.addVertex(pose, 1f, 0f, 0f).setColor(255, 255, 255, 255).setUv(1f, 1f).setLight(0xF000F0)
                .setNormal(0f, 0f, 1f)
            builder.addVertex(pose, 1f, 1f, 0f).setColor(255, 255, 255, 255).setUv(1f, 0f).setLight(0xF000F0)
                .setNormal(0f, 0f, 1f)
            builder.addVertex(pose, 0f, 1f, 0f).setColor(255, 255, 255, 255).setUv(0f, 0f).setLight(0xF000F0)
                .setNormal(0f, 0f, 1f)
        }
    }

    /** Draws a unit quad filled with a solid RGB color (used for loading / error state). */
    private fun renderColor(drawQuad: QuadRenderer, type: RenderType, r: Int, g: Int, b: Int) {
        drawQuad(type) { pose, builder ->
            builder.addVertex(pose, 0f, 0f, 0f).setColor(r, g, b, 255).setUv(0f, 1f).setLight(0xF000F0)
                .setNormal(0f, 0f, 1f)
            builder.addVertex(pose, 1f, 0f, 0f).setColor(r, g, b, 255).setUv(1f, 1f).setLight(0xF000F0)
                .setNormal(0f, 0f, 1f)
            builder.addVertex(pose, 1f, 1f, 0f).setColor(r, g, b, 255).setUv(1f, 0f).setLight(0xF000F0)
                .setNormal(0f, 0f, 1f)
            builder.addVertex(pose, 0f, 1f, 0f).setColor(r, g, b, 255).setUv(0f, 0f).setLight(0xF000F0)
                .setNormal(0f, 0f, 1f)
        }
    }

    private fun drawImmediate(stack: PoseStack, type: RenderType, appendVertices: QuadAppender) {
        val builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
        appendVertices(stack.last(), builder)
        type.draw(builder.buildOrThrow())
    }
}
