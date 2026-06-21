package com.dreamdisplays.core.media

import com.dreamdisplays.core.display.DisplayId
import kotlin.time.Duration

interface MediaSession : AutoCloseable {
    val sessionId: String
    val displayId: DisplayId
    val state: MediaSessionState
    val currentPosition: Duration
    val duration: Duration?
    val metadata: MediaMetadata

    fun play()
    fun pause()
    fun seek(position: Duration)
    fun setVolume(volume: Float)
    fun on(listener: (MediaSessionEvent) -> Unit): AutoCloseable
}
