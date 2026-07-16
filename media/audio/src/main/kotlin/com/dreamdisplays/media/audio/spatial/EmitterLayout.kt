package com.dreamdisplays.media.audio.spatial

import com.dreamdisplays.media.audio.math.Vec3
import kotlin.math.max
import kotlin.math.pow

/** Geometry helpers for a two-emitter (L / R) planar-screen area source. */
object EmitterLayout {
    /** World position of the emitter carrying the left content channel: 25% in from the left edge. */
    fun leftEmitter(center: Vec3, uAxis: Vec3, width: Double): Vec3 = center - uAxis * (width * 0.25)

    /** World position of the emitter carrying the right content channel: 25% in from the right edge. */
    fun rightEmitter(center: Vec3, uAxis: Vec3, width: Double): Vec3 = center + uAxis * (width * 0.25)

    /**
     * Inverse-distance-with-rolloff attenuation (`OpenAL`'s `AL_INVERSE_DISTANCE_CLAMPED` model): flat at
     * 1.0 inside [refDistance] (the screen's own footprint, so a huge display never blows up when the
     * listener stands close to it), rolling off past it.
     */
    fun distanceGain(distance: Double, refDistance: Double, rolloff: Double = 1.0): Double {
        val ref = max(refDistance, 0.1)
        return ref / (ref + rolloff * max(0.0, distance - ref))
    }

    /**
     * Broadband directivity gain from the angle between the plane's outward [normal] and the unit
     * [toListenerDir]: near 1 in front of the screen, floored at [backFloor] directly behind it.
     */
    fun directivityGain(normal: Vec3, toListenerDir: Vec3, backFloor: Double = 0.35, exponent: Double = 1.5): Double {
        val cosTheta = (normal dot toListenerDir).coerceIn(-1.0, 1.0)
        val front = ((cosTheta + 1.0) / 2.0).pow(exponent)
        return backFloor + (1.0 - backFloor) * front
    }
}
