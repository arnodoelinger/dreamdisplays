package com.dreamdisplays.api.media


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
