package com.dreamdisplays.render

import com.dreamdisplays.display.DisplayManager
import com.dreamdisplays.display.DisplayScreen
import com.mojang.blaze3d.vertex.*
import net.minecraft.client.Camera
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import kotlin.math.abs
import kotlin.math.sin

/** Renders screens in the world. */
object ScreenRenderer {
    private typealias QuadAppender = (PoseStack.Pose, VertexConsumer) -> Unit
    private typealias QuadRenderer = (RenderType, QuadAppender) -> Unit

    /** Iterates all registered screens and renders each one relative to [camera]. */
    fun render(stack: PoseStack, camera: Camera) {
        render(stack, camera) { type, appendVertices ->
            drawImmediate(stack, type, appendVertices)
        }
    }

    /** Iterates all registered screens and lets the caller submit quads through the active renderer. */
    fun render(stack: PoseStack, camera: Camera, drawQuad: QuadRenderer) {
        //? if >=1.21.11 {
        val cameraPos = camera.position()
        //?} else
        /*val cameraPos = camera.position*/
        for (displayScreen in DisplayManager.getScreens()) {
            if (displayScreen.texture == null) continue

            stack.pushPose()

            val pos = displayScreen.pos
            val screenCenter = Vec3.atLowerCornerOf(pos)
            val relativePos = screenCenter.subtract(cameraPos)
            stack.translate(relativePos.x, relativePos.y, relativePos.z)

            renderScreenTexture(displayScreen, stack, drawQuad)

            stack.popPose()
        }
    }

    /** Translates and rotates the pose for [displayScreen]'s facing direction, then renders the video or fallback color. */
    private fun renderScreenTexture(displayScreen: DisplayScreen, stack: PoseStack, drawQuad: QuadRenderer) {
        // Upload the latest decoded frame to the GPU texture (if a new one is ready).
        // Done here on the render thread instead of via mc.execute() per frame.
        displayScreen.fitTexture()

        stack.pushPose()
        moveForward(stack, displayScreen.facing, 0.008f)

        when (displayScreen.facing) {
            "NORTH" -> {
                moveHorizontal(stack, "NORTH", -displayScreen.width.toFloat())
                moveForward(stack, "NORTH", 1f)
            }

            "SOUTH" -> {
                moveHorizontal(stack, "SOUTH", 1f)
                moveForward(stack, "SOUTH", 1f)
            }

            "EAST" -> {
                moveHorizontal(stack, "EAST", -(displayScreen.width - 1).toFloat())
                moveForward(stack, "EAST", 2f)
            }
        }

        fixRotation(stack, displayScreen.facing)
        stack.scale(displayScreen.width.toFloat(), displayScreen.height.toFloat(), 0f)

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

    /** Applies the quaternion rotation and position correction so the quad faces the correct direction for [facing]. */
    private fun fixRotation(stack: PoseStack, facing: String) {
        val rotation: Quaternionf = when (facing) {
            "NORTH" -> Quaternionf().rotationY(Math.toRadians(180.0).toFloat()).also {
                stack.translate(0f, 0f, 1f)
            }

            "WEST" -> Quaternionf().rotationY(Math.toRadians(-90.0).toFloat()).also {
                stack.translate(0f, 0f, 0f)
            }

            "EAST" -> Quaternionf().rotationY(Math.toRadians(90.0).toFloat()).also {
                stack.translate(-1f, 0f, 1f)
            }

            else -> Quaternionf().also { stack.translate(-1f, 0f, 0f) }
        }
        stack.mulPose(rotation)
    }

    /** Translates [stack] by [amount] blocks in the forward axis of [facing]. */
    private fun moveForward(stack: PoseStack, facing: String, amount: Float) {
        when (facing) {
            "NORTH" -> stack.translate(0f, 0f, -amount)
            "WEST" -> stack.translate(-amount, 0f, 0f)
            "EAST" -> stack.translate(amount, 0f, 0f)
            else -> stack.translate(0f, 0f, amount)
        }
    }

    /** Translates [stack] by [amount] blocks along the horizontal axis perpendicular to [facing]. */
    private fun moveHorizontal(stack: PoseStack, facing: String, amount: Float) {
        when (facing) {
            "NORTH" -> stack.translate(-amount, 0f, 0f)
            "WEST" -> stack.translate(0f, 0f, amount)
            "EAST" -> stack.translate(0f, 0f, -amount)
            else -> stack.translate(amount, 0f, 0f)
        }
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
