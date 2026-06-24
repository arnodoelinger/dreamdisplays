package com.dreamdisplays.media.player.decode

import com.dreamdisplays.api.media.sink.AudioSink
import com.dreamdisplays.api.media.sink.VideoFrameSink
import com.dreamdisplays.media.player.stream.StreamSet

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
