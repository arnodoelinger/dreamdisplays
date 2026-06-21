package com.dreamdisplays.api.media


fun interface VideoFrameSink {
    fun onFrame(frame: DecodedVideoFrame)

    companion object {
        val DISCARD: VideoFrameSink = VideoFrameSink { }
    }
}
