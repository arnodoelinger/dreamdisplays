package com.dreamdisplays.render.api

data class UploadBudget(
    val maxUploadsPerFrame: Int,
    val maxBytesPerFrame: Long,
    val targetFps: Int,
) {
    companion object {
        val DEFAULT = UploadBudget(
            maxUploadsPerFrame = 4,
            maxBytesPerFrame = 8 * 1024 * 1024L,
            targetFps = 30,
        )
    }
}
