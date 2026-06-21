package com.dreamdisplays.api.media.player

/** Read-only playback-relevant configuration the media player needs from the host application. */
interface PlaybackConfig {
    /** Default per-display volume (0–2.0) applied to a freshly created player. */
    val defaultDisplayVolume: Double

    /** Whether hardware-accelerated decoding should be attempted. */
    val useHwAccel: Boolean

    /** Whether the current user may use premium quality tiers (e.g. >1080p). */
    val isPremium: Boolean

    /** Whether the GPU-side YUV (planar I420) render path is active; selects the planar decode output. */
    val gpuYuvActive: Boolean
}
