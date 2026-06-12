@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.media.api

import com.dreamdisplays.api.DreamDisplaysUnstableApi

import com.dreamdisplays.api.DisplayId
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
