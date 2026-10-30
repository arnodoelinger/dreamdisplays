package com.dreamdisplays.api.display.geometry

import com.dreamdisplays.api.display.model.property.DisplayFacing
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The block-space math `Dream Displays` itself uses for its displays: how far a point is from
 * a display, whether it is inside one, where a display's center and axes are.
 *
 * A display is described by its minimum corner block, its width and height in blocks, its facing
 * and its depth (blocks it extends away from the wall, `1` for a flat display).
 *
 * @since 1.10.x
 */
object DisplayGeometry {
    private inline fun <T> withBounds(
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int,
        block: (maxX: Int, maxY: Int, maxZ: Int) -> T,
    ): T {
        var maxX = minX
        var maxZ = minZ
        var maxY = minY + height - 1
        when (facing) {
            DisplayFacing.NORTH, DisplayFacing.SOUTH -> {
                maxX += width - 1
                maxZ += depth - 1
            }
            DisplayFacing.EAST, DisplayFacing.WEST -> {
                maxZ += width - 1
                maxX += depth - 1
            }
            DisplayFacing.UP, DisplayFacing.DOWN -> {
                maxX += width - 1
                maxZ += height - 1
                maxY = minY + depth - 1
            }
        }
        return block(maxX, maxY, maxZ)
    }

    /** True when the block ([x], [y], [z]) is one of the display's blocks. */
    @JvmStatic
    @JvmOverloads
    fun isInBounds(
        x: Int, y: Int, z: Int,
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int = 1,
    ): Boolean = withBounds(minX, minY, minZ, width, height, facing, depth) { maxX, maxY, maxZ ->
        x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }

    /** True when the point ([x], [y], [z]) lies inside the display's box. */
    @JvmStatic
    @JvmOverloads
    fun isInBounds(
        x: Double, y: Double, z: Double,
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int = 1,
    ): Boolean = withBounds(minX, minY, minZ, width, height, facing, depth) { maxX, maxY, maxZ ->
        x >= minX && x <= maxX + 1 && y >= minY && y <= maxY + 1 && z >= minZ && z <= maxZ + 1
    }

    /** Distance from the block ([x], [y], [z]) to the nearest block of the display. */
    @JvmStatic
    @JvmOverloads
    fun distanceTo(
        x: Int, y: Int, z: Int,
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int = 1,
    ): Double = withBounds(minX, minY, minZ, width, height, facing, depth) { maxX, maxY, maxZ ->
        val dx = (x - min(max(x, minX), maxX)).toLong()
        val dy = (y - min(max(y, minY), maxY)).toLong()
        val dz = (z - min(max(z, minZ), maxZ)).toLong()
        sqrt((dx * dx + dy * dy + dz * dz).toDouble())
    }

    /** Distance from the point ([x], [y], [z]) to the display's box; `0` inside it. */
    @JvmStatic
    @JvmOverloads
    fun distanceTo(
        x: Double, y: Double, z: Double,
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int = 1,
    ): Double = withBounds(minX, minY, minZ, width, height, facing, depth) { maxX, maxY, maxZ ->
        val dx = x - min(max(x, minX.toDouble()), maxX + 1.0)
        val dy = y - min(max(y, minY.toDouble()), maxY + 1.0)
        val dz = z - min(max(z, minZ.toDouble()), maxZ + 1.0)
        sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Writes the center (`out[0..2]`) and radius (`out[3]`) of the sphere around the display's box into [out],
     * without allocating.
     *
     * Used for per-frame culling.
     */
    @JvmStatic
    fun boundingSphere(
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int, out: DoubleArray,
    ) {
        withBounds(minX, minY, minZ, width, height, facing, depth) { maxX, maxY, maxZ ->
            val sx = (maxX + 1 - minX).toDouble()
            val sy = (maxY + 1 - minY).toDouble()
            val sz = (maxZ + 1 - minZ).toDouble()
            out[0] = minX + sx * 0.5
            out[1] = minY + sy * 0.5
            out[2] = minZ + sz * 0.5
            out[3] = 0.5 * sqrt(sx * sx + sy * sy + sz * sz)
        }
    }

    /**
     * The display's center and axes. Block-center precision with no offset from the surface: it's good for placing a
     * sound source or an effect, but, obviously, not for drawing the picture.
     */
    @JvmStatic
    @JvmOverloads
    fun pose(
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int = 1,
    ): DisplayPose = withBounds(minX, minY, minZ, width, height, facing, depth) { maxX, maxY, maxZ ->
        val cx = (minX + maxX + 1) / 2.0
        val cy = (minY + maxY + 1) / 2.0
        val cz = (minZ + maxZ + 1) / 2.0
        val (nx, ny, nz) = when (facing) {
            DisplayFacing.NORTH -> Triple(0.0, 0.0, -1.0)
            DisplayFacing.SOUTH -> Triple(0.0, 0.0, 1.0)
            DisplayFacing.EAST -> Triple(1.0, 0.0, 0.0)
            DisplayFacing.WEST -> Triple(-1.0, 0.0, 0.0)
            DisplayFacing.UP -> Triple(0.0, 1.0, 0.0)
            DisplayFacing.DOWN -> Triple(0.0, -1.0, 0.0)
        }
        val (ux, uy, uz) = when (facing) {
            DisplayFacing.NORTH -> Triple(-1.0, 0.0, 0.0)
            DisplayFacing.SOUTH -> Triple(1.0, 0.0, 0.0)
            DisplayFacing.EAST -> Triple(0.0, 0.0, -1.0)
            DisplayFacing.WEST -> Triple(0.0, 0.0, 1.0)
            DisplayFacing.UP, DisplayFacing.DOWN -> Triple(1.0, 0.0, 0.0)
        }
        val (vx, vy, vz) = when (facing) {
            DisplayFacing.UP, DisplayFacing.DOWN -> Triple(0.0, 0.0, 1.0)
            else -> Triple(0.0, 1.0, 0.0)
        }
        DisplayPose(cx, cy, cz, nx, ny, nz, ux, uy, uz, vx, vy, vz)
    }
}
