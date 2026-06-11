package com.dreamdisplays.media

import com.dreamdisplays.api.DisplayId
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.media.api.MediaSession
import com.dreamdisplays.media.api.MediaSessionManager

/** Default [MediaSessionManager] backed by [DisplayRegistry]'s loaded screens. */
class DefaultMediaSessionManager : MediaSessionManager {

    /** Opens a [DisplayMediaSession] for [displayId], or null if the display is not loaded. */
    override fun open(displayId: DisplayId): MediaSession? =
        DisplayRegistry.screens[displayId.uuid]?.let { DisplayMediaSession(it) }

    /** Sessions for every loaded display that has media assigned. */
    override fun activeSessions(): List<MediaSession> =
        DisplayRegistry.getScreens()
            .filter { !it.videoUrl.isNullOrEmpty() }
            .map { DisplayMediaSession(it) }
}
