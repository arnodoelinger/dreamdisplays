@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.media.api

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.core.display.DisplayId

/** Hands out [MediaSession] views onto playing displays. */
interface MediaSessionManager {
    fun open(displayId: DisplayId): MediaSession?
    fun activeSessions(): List<MediaSession>
}
