package com.dreamdisplays.api.media.audio.model

import com.dreamdisplays.api.Unstable

/**
 * World-space planar sound source (rectangle with center, axes, and normal); one block = one meter.
 *
 * @since 1.9.x
 */
@Unstable
data class SourcePlane(
    val centerX: Double,
    val centerY: Double,
    val centerZ: Double,
    val normalX: Double,
    val normalY: Double,
    val normalZ: Double,
    val uAxisX: Double,
    val uAxisY: Double,
    val uAxisZ: Double,
    val vAxisX: Double,
    val vAxisY: Double,
    val vAxisZ: Double,
    val width: Double,
    val height: Double,
)
