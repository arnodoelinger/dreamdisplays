package com.dreamdisplays.api.display.geometry

/**
 * Where a display is and which way it faces.
 *
 * @since 1.10.x
 */
data class DisplayPose(
    val centerX: Double, val centerY: Double, val centerZ: Double,
    val normalX: Double, val normalY: Double, val normalZ: Double,
    val uAxisX: Double, val uAxisY: Double, val uAxisZ: Double,
    val vAxisX: Double, val vAxisY: Double, val vAxisZ: Double,
)
