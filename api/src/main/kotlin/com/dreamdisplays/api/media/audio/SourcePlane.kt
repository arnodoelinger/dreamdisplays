package com.dreamdisplays.api.media.audio

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * World-space planar sound source: a rectangle centered at ([centerX], [centerY], [centerZ]) spanning
 * [width] blocks along the unit [uAxisX] / [uAxisY] / [uAxisZ] and [height] blocks along the unit
 * [vAxisX] / [vAxisY] / [vAxisZ], facing along the outward unit normal
 * [normalX] / [normalY] / [normalZ].
 *
 * One block is treated as one meter for acoustic purposes.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
data class SourcePlane(
    val centerX: Double, val centerY: Double, val centerZ: Double,
    val normalX: Double, val normalY: Double, val normalZ: Double,
    val uAxisX: Double, val uAxisY: Double, val uAxisZ: Double,
    val vAxisX: Double, val vAxisY: Double, val vAxisZ: Double,
    val width: Double, val height: Double,
)
