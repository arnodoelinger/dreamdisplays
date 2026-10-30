package com.dreamdisplays.api.display.placement

import com.dreamdisplays.api.Experimental
import com.dreamdisplays.api.display.model.property.DisplayId

/**
 * A point or vector in 3D space.
 *
 * @since 1.10.x
 */
@Experimental
data class Vec3(val x: Double, val y: Double, val z: Double)

/**
 * How a display's own block space maps into the world at one moment.
 *
 * @since 1.10.x
 */
@Experimental
class PlacementTransform @JvmOverloads constructor(
    val anchorX: Int, val anchorY: Int, val anchorZ: Int,
    val originX: Double, val originY: Double, val originZ: Double,
    val rotationX: Double = 0.0, val rotationY: Double = 0.0, val rotationZ: Double = 0.0, val rotationW: Double = 1.0,
    val scaleX: Double = 1.0, val scaleY: Double = 1.0, val scaleZ: Double = 1.0,
) {
    /** Rotates the vector ([x], [y], [z]) by the transform's rotation (no move, no scale). */
    fun rotate(x: Double, y: Double, z: Double): Vec3 = rotateBy(x, y, z, rotationX, rotationY, rotationZ, rotationW)

    /** Undoes [rotate]. */
    fun inverseRotate(x: Double, y: Double, z: Double): Vec3 = rotateBy(x, y, z, -rotationX, -rotationY, -rotationZ, rotationW)

    /** The world position of the local-space point ([x], [y], [z]). */
    fun toWorld(x: Double, y: Double, z: Double): Vec3 {
        val r = rotate((x - anchorX) * scaleX, (y - anchorY) * scaleY, (z - anchorZ) * scaleZ)
        return Vec3(originX + r.x, originY + r.y, originZ + r.z)
    }

    /** The local-space position of the world point ([x], [y], [z]). */
    fun toLocal(x: Double, y: Double, z: Double): Vec3 {
        val r = inverseRotate(x - originX, y - originY, z - originZ)
        return Vec3(anchorX + r.x / scaleX, anchorY + r.y / scaleY, anchorZ + r.z / scaleZ)
    }

    private fun rotateBy(x: Double, y: Double, z: Double, qx: Double, qy: Double, qz: Double, qw: Double): Vec3 {
        val tx = 2 * (qy * z - qz * y)
        val ty = 2 * (qz * x - qx * z)
        val tz = 2 * (qx * y - qy * x)
        return Vec3(
            x + qw * tx + (qy * tz - qz * ty),
            y + qw * ty + (qz * tx - qx * tz),
            z + qw * tz + (qx * ty - qy * tx),
        )
    }
}

/**
 * Says where one display really is right now.
 *
 * @since 1.10.x
 */
@Experimental
fun interface DisplayPlacement {
    /**
     * The transform at [partialTick] (0..1 between game ticks).
     *
     * Called every frame.
     */
    fun transform(partialTick: Float): PlacementTransform
}

/**
 * Supplies placements. Asked for every display, often (every frame), so look the display up in a map and answer `null`
 * when it is not yours.
 *
 * @since 1.10.x
 */
@Experimental
fun interface DisplayPlacementProvider {
    /** The placement of [displayId], or `null` when the display sits at its stored coordinates. */
    fun placementOf(displayId: DisplayId): DisplayPlacement?
}

/**
 * Where [DisplayPlacementProvider]s are registered.
 *
 * @since 1.10.x
 */
@Experimental
interface DisplayPlacements {
    /** Registers [provider]; close the handle (or end the session that owns it) to remove it. */
    fun register(provider: DisplayPlacementProvider): AutoCloseable
}
