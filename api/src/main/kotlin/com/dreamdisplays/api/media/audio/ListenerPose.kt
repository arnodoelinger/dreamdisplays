package com.dreamdisplays.api.media.audio

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * World-space listener pose: position plus a forward / up orthonormal basis, used to compute azimuth
 * and directivity for every registered [SourcePlane].
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
data class ListenerPose(
    val x: Double, val y: Double, val z: Double,
    val forwardX: Double, val forwardY: Double, val forwardZ: Double,
    val upX: Double, val upY: Double, val upZ: Double,
) {
    companion object {
        /** Origin-facing pose used before the first real camera update arrives. */
        val IDENTITY = ListenerPose(0.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0)
    }
}
