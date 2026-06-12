@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.media.api

import com.dreamdisplays.api.DreamDisplaysUnstableApi

import kotlin.time.Duration

data class MediaMetadata(
    val title: String?,
    val uploader: String?,
    val duration: Duration?,
    val thumbnailUrl: String?,
    val viewCount: Long?,
    val likeCount: Long?,
    val uploadDate: String?,
) {
    companion object {
        val UNKNOWN = MediaMetadata(null, null, null, null, null, null, null)
    }
}
