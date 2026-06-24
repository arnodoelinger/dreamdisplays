package com.dreamdisplays.media.runtime

import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.media.session.MediaSession

/** Hands out [MediaSession] views onto playing displays. */
interface MediaSessionManager {
    /**
     * Opens a session handle for [displayId], or null if no such display is loaded.
     * Closing the handle detaches its listeners; it never stops playback.
     */
    fun open(displayId: DisplayId): MediaSession?

    /** Fresh session handles for every loaded display that currently has media. */
    fun activeSessions(): List<MediaSession>
}
