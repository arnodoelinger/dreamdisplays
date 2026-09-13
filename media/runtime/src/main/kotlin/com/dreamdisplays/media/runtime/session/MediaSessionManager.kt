package com.dreamdisplays.media.runtime.session

import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.media.session.service.MediaSessionService

/** Hands out [MediaSessionService] views onto playing displays. */
interface MediaSessionManager {
    /**
     * Opens a session handle for [displayId], or null if no such display is loaded.
     * Closing the handle detaches its listeners; it never stops playback.
     */
    fun open(displayId: DisplayId): MediaSessionService?

    /** Fresh session handles for every loaded display that currently has media. */
    fun activeSessions(): List<MediaSessionService>
}
