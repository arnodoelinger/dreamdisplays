package com.dreamdisplays.api.media

import com.dreamdisplays.api.display.model.DisplayId

/** Hands out [MediaSession] views onto playing displays. */
interface MediaSessionManager {
    fun open(displayId: DisplayId): MediaSession?
    fun activeSessions(): List<MediaSession>
}
