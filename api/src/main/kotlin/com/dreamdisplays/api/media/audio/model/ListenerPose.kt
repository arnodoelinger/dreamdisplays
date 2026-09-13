package com.dreamdisplays.api.media.audio.model

import com.dreamdisplays.api.Unstable

/**
 * World-space listener pose (position + forward / up basis) for azimuth and directivity.
 *
 * @since 1.9.x
 */
@Unstable
data class ListenerPose(
    val x: Double,
    val y: Double,
    val z: Double,
    val forwardX: Double,
    val forwardY: Double,
    val forwardZ: Double,
    val upX: Double,
    val upY: Double,
    val upZ: Double,
) {
    companion object {
        /** Origin-facing pose used before the first real camera update arrives. */
        val IDENTITY = ListenerPose(0.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0)
    }
}
