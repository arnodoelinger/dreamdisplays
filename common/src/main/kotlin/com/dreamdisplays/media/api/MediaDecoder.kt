@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.media.api

import com.dreamdisplays.api.DreamDisplaysUnstableApi

interface MediaDecoder : AutoCloseable {
    val isRunning: Boolean

    fun start(
        stream: StreamSet,
        frameSink: VideoFrameSink,
        audioSink: AudioSink,
    )

    fun stop()
    fun seek(positionMs: Long)
}
