@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.media.api

import com.dreamdisplays.api.DreamDisplaysUnstableApi

fun interface VideoFrameSink {
    fun onFrame(frame: DecodedVideoFrame)

    companion object {
        val DISCARD: VideoFrameSink = VideoFrameSink { }
    }
}
