package com.dreamdisplays.render.api

data class RenderStats(
    val decodedFps: Float,
    val uploadedFps: Float,
    val droppedFrames: Int,
    val lastUploadLatencyMs: Long,
    val textureMemoryBytes: Long,
) {
    companion object {
        val EMPTY = RenderStats(0f, 0f, 0, 0L, 0L)
    }
}
