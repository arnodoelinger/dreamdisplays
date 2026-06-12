@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.media.api

import com.dreamdisplays.api.DreamDisplaysUnstableApi

data class MediaStream(
    val url: String,
    val type: MediaStreamType,
    val codec: String?,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val bitrate: Int?,
    val audioTrackName: String?,
    val audioTrackLang: String?,
    val isDefault: Boolean = false,
) {
    val qualityLabel: String get() = when {
        height != null && fps != null && fps > 50 -> "${height}p${fps.toInt()}"
        height != null -> "${height}p"
        bitrate != null -> "${bitrate / 1000}kbps"
        else -> "unknown"
    }
}
