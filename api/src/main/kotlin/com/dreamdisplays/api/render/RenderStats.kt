package com.dreamdisplays.api.render

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/** Rolling render and texture-upload counters for display diagnostics. */
@DreamDisplaysUnstableApi data class RenderStats(
    /** Frames decoded per second. */
    val decodedFps: Float,

    /** Frames uploaded to GPU textures per second. */
    val uploadedFps: Float,

    /** Number of frames dropped by the upload queue. */
    val droppedFrames: Int,

    /** Latency of the last texture upload in milliseconds. */
    val lastUploadLatencyMs: Long,

    /** Estimated texture memory used by registered display surfaces. */
    val textureMemoryBytes: Long,
) {
    companion object {
        /** Zero-valued stats snapshot. */
        val EMPTY = RenderStats(0f, 0f, 0, 0L, 0L)
    }
}
