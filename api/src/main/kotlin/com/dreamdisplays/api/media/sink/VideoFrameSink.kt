package com.dreamdisplays.api.media.sink

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/** Consumer for decoded video frames. Usually implemented by a texture upload queue. */
@DreamDisplaysUnstableApi fun interface VideoFrameSink {
    /** Accepts one decoded [frame]. */
    fun onFrame(frame: DecodedVideoFrame)

    companion object {
        /** Sink that intentionally drops every frame. */
        val DISCARD: VideoFrameSink = VideoFrameSink { }
    }
}
