package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.display.model.DisplayFacing
import com.dreamdisplays.platform.client.render.DisplayGeometry.SURFACE_OFFSET
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.core.BlockPos
import org.joml.Quaternionf
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pose-stack math for positioning a display quad in the world: facing-dependent translation,
 * rotation, and scaling. Extracted from [ScreenRenderer] so the renderer only sequences draws.
 *
 * Also owns the block-space bounding-box math (containment and distance) shared by display logic.
 */
internal object DisplayGeometry {
    /**
     * Computes the inclusive far corner of a screen anchored at ([x], [y], [z]) with [width] x [height] blocks
     * facing [facing]. Width extends along the horizontal axis perpendicular to [facing]; height extends up.
     */
    private inline fun <T> withBounds(
        x: Int, y: Int, z: Int, width: Int, height: Int, facing: DisplayFacing,
        block: (maxX: Int, maxY: Int, maxZ: Int) -> T,
    ): T {
        var maxX = x
        var maxZ = z
        var maxY = y + height - 1
        when (facing) {
            DisplayFacing.NORTH, DisplayFacing.SOUTH -> maxX += width - 1
            DisplayFacing.EAST, DisplayFacing.WEST -> maxZ += width - 1
            DisplayFacing.UP, DisplayFacing.DOWN -> {
                maxX += width - 1
                maxZ += height - 1
                maxY = y
            }
        }
        return block(maxX, maxY, maxZ)
    }

    /** Returns true if [pos] falls within the block bounding box of the described screen. */
    fun isInBounds(pos: BlockPos, x: Int, y: Int, z: Int, width: Int, height: Int, facing: DisplayFacing): Boolean =
        withBounds(x, y, z, width, height, facing) { maxX, maxY, maxZ ->
            pos.x in x..maxX && pos.y in y..maxY && pos.z in z..maxZ
        }

    /** Returns the shortest Euclidean distance from [pos] to any block in the described screen's bounding box. */
    fun distanceTo(pos: BlockPos, x: Int, y: Int, z: Int, width: Int, height: Int, facing: DisplayFacing): Double =
        withBounds(x, y, z, width, height, facing) { maxX, maxY, maxZ ->
            val clampedX = min(max(pos.x, x), maxX)
            val clampedY = min(max(pos.y, y), maxY)
            val clampedZ = min(max(pos.z, z), maxZ)
            sqrt(pos.distSqr(BlockPos(clampedX, clampedY, clampedZ)))
        }

    /**
     * World-space center and orientation basis for a screen anchored at ([x], [y], [z]) with the given
     * [width] / [height] / [facing]. Approximate (block-center precision, no surface offset): good
     * enough for placing acoustic emitters, not for rendering.
     */
    fun worldPose(x: Int, y: Int, z: Int, width: Int, height: Int, facing: DisplayFacing): ScreenPose =
        withBounds(x, y, z, width, height, facing) { maxX, maxY, maxZ ->
            val cx = (x + maxX + 1) / 2.0
            val cy = (y + maxY + 1) / 2.0
            val cz = (z + maxZ + 1) / 2.0
            val (nx, ny, nz) = when (facing) {
                DisplayFacing.NORTH -> Triple(0.0, 0.0, -1.0)
                DisplayFacing.SOUTH -> Triple(0.0, 0.0, 1.0)
                DisplayFacing.EAST -> Triple(1.0, 0.0, 0.0)
                DisplayFacing.WEST -> Triple(-1.0, 0.0, 0.0)
                DisplayFacing.UP -> Triple(0.0, 1.0, 0.0)
                DisplayFacing.DOWN -> Triple(0.0, -1.0, 0.0)
            }
            val (ux, uy, uz) = when (facing) {
                DisplayFacing.NORTH, DisplayFacing.SOUTH, DisplayFacing.UP, DisplayFacing.DOWN -> Triple(1.0, 0.0, 0.0)
                DisplayFacing.EAST, DisplayFacing.WEST -> Triple(0.0, 0.0, 1.0)
            }
            val (vx, vy, vz) = when (facing) {
                DisplayFacing.UP, DisplayFacing.DOWN -> Triple(0.0, 0.0, 1.0)
                else -> Triple(0.0, 1.0, 0.0)
            }
            ScreenPose(cx, cy, cz, nx, ny, nz, ux, uy, uz, vx, vy, vz)
        }

    /**
     * Distance the quad floats in front of the supporting blocks, in local block space. This is a
     * secondary safety margin only: the primary defense against z-fighting is the GPU polygon-offset
     * bias baked into the render pipeline itself (see `RenderPipelineCompat.configureDepth` /
     * `DisplayUnlitRenderTypes`'s `POLYGON_OFFSET_LAYERING`), which operates in depth-buffer units and
     * so stays effective at any camera distance.
     */
    private const val SURFACE_OFFSET = 0.008f

    /**
     * Larger surface offset used while a shader pack is active. Shader packs (notably OptiFine /
     * SEUS Renewed) drive the depth buffer with far coarser precision than vanilla, so the tiny
     * [SURFACE_OFFSET] no longer separates the video plane from the backing block: past a few blocks
     * the block wins the depth test and the screen reverts to the bare block. A wider gap keeps the
     * quad in front across the whole render distance.
     *
     * @see <a href="https://github.com/arnodoelinger/dreamdisplays/issues/108">Issue #108</a>
     */
    private const val SHADER_SURFACE_OFFSET = 0.016f

    /** Surface offset in effect right now: widened while a shader pack owns the depth buffer. */
    private fun surfaceOffset(): Float =
        if (ShaderPackCompat.isShaderPackActive) SHADER_SURFACE_OFFSET else SURFACE_OFFSET

    /**
     * Applies the full transform for a screen of [width] x [height] blocks facing [facing]:
     * surface offset, per-facing corner correction, rotation, and scale. The stack is expected to
     * already be translated to the screen's anchor block.
     */
    fun applyScreenTransform(stack: PoseStack, facing: DisplayFacing, width: Int, height: Int) {
        moveForward(stack, facing, surfaceOffset())

        when (facing) {
            DisplayFacing.NORTH -> {
                moveHorizontal(stack, DisplayFacing.NORTH, -width.toFloat())
                moveForward(stack, DisplayFacing.NORTH, 1f)
            }

            DisplayFacing.SOUTH -> {
                moveHorizontal(stack, DisplayFacing.SOUTH, 1f)
                moveForward(stack, DisplayFacing.SOUTH, 1f)
            }

            DisplayFacing.EAST -> {
                moveHorizontal(stack, DisplayFacing.EAST, -(width - 1).toFloat())
                moveForward(stack, DisplayFacing.EAST, 2f)
            }

            DisplayFacing.WEST -> {}

            DisplayFacing.UP -> {
                stack.translate(0f, 1f, 0f)
                stack.translate(0f, 0f, height.toFloat())
            }

            DisplayFacing.DOWN -> {}
        }

        fixRotation(stack, facing)
        stack.scale(width.toFloat(), height.toFloat(), 0f)
    }

    /** Applies the quaternion rotation and position correction so the quad faces the correct direction for [facing]. */
    private fun fixRotation(stack: PoseStack, facing: DisplayFacing) {
        val rotation: Quaternionf = when (facing) {
            DisplayFacing.NORTH -> Quaternionf().rotationY(Math.toRadians(180.0).toFloat()).also {
                stack.translate(0f, 0f, 1f)
            }

            DisplayFacing.WEST -> Quaternionf().rotationY(Math.toRadians(-90.0).toFloat()).also {
                stack.translate(0f, 0f, 0f)
            }

            DisplayFacing.EAST -> Quaternionf().rotationY(Math.toRadians(90.0).toFloat()).also {
                stack.translate(-1f, 0f, 1f)
            }

            DisplayFacing.SOUTH -> Quaternionf().also { stack.translate(-1f, 0f, 0f) }

            DisplayFacing.UP -> Quaternionf().rotationX(Math.toRadians(-90.0).toFloat())

            DisplayFacing.DOWN -> Quaternionf().rotationX(Math.toRadians(90.0).toFloat())
        }
        stack.mulPose(rotation)
    }

    /**
     * Nudges [stack] [amount] blocks toward the viewer along [facing]'s outward normal. Call this
     * before [applyScreenTransform] to lift an overlay layer off the video plane: the transform's
     * final `scale(w, h, 0)` flattens local z to zero, so coplanar quads can only be depth-separated
     * by offsetting them in world space here, ahead of that scale.
     */
    fun liftTowardViewer(stack: PoseStack, facing: DisplayFacing, amount: Float) = moveForward(stack, facing, amount)

    /** Translates [stack] by [amount] blocks in the forward axis of [facing]. */
    private fun moveForward(stack: PoseStack, facing: DisplayFacing, amount: Float) {
        when (facing) {
            DisplayFacing.NORTH -> stack.translate(0f, 0f, -amount)
            DisplayFacing.WEST -> stack.translate(-amount, 0f, 0f)
            DisplayFacing.EAST -> stack.translate(amount, 0f, 0f)
            DisplayFacing.SOUTH -> stack.translate(0f, 0f, amount)
            DisplayFacing.UP -> stack.translate(0f, amount, 0f)
            DisplayFacing.DOWN -> stack.translate(0f, -amount, 0f)
        }
    }

    /** Translates [stack] by [amount] blocks along the horizontal axis perpendicular to [facing]. */
    private fun moveHorizontal(stack: PoseStack, facing: DisplayFacing, amount: Float) {
        when (facing) {
            DisplayFacing.NORTH -> stack.translate(-amount, 0f, 0f)
            DisplayFacing.WEST -> stack.translate(0f, 0f, amount)
            DisplayFacing.EAST -> stack.translate(0f, 0f, -amount)
            DisplayFacing.SOUTH -> stack.translate(amount, 0f, 0f)
            DisplayFacing.UP, DisplayFacing.DOWN -> {}
        }
    }
}

/** World-space center + orthonormal (normal, uAxis, vAxis) basis for a screen, see [DisplayGeometry.worldPose]. */
internal data class ScreenPose(
    val centerX: Double, val centerY: Double, val centerZ: Double,
    val normalX: Double, val normalY: Double, val normalZ: Double,
    val uAxisX: Double, val uAxisY: Double, val uAxisZ: Double,
    val vAxisX: Double, val vAxisY: Double, val vAxisZ: Double,
)
