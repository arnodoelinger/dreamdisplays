package com.dreamdisplays.media.audio.math

import kotlin.math.sqrt

/** Minimal double-precision 3-vector for source / listener geometry math. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)

    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3): Vec3 = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun length(): Double = sqrt(this dot this)

    fun normalized(): Vec3 {
        val len = length()
        return if (len < 1e-9) Vec3(0.0, 0.0, 0.0) else Vec3(x / len, y / len, z / len)
    }
}
