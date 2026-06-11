package com.dreamdisplays.media.api

fun interface VideoFrameSink {
    fun onFrame(frame: DecodedVideoFrame)

    companion object {
        val DISCARD: VideoFrameSink = VideoFrameSink { }
    }
}
