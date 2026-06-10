package com.dreamdisplays.media

import com.dreamdisplays.api.DisplayId
import com.dreamdisplays.displays.DisplayManager
import com.dreamdisplays.media.api.MediaSession
import com.dreamdisplays.media.api.MediaSessionManager

/** Default [MediaSessionManager] backed by [DisplayManager]'s loaded screens. */
class DefaultMediaSessionManager : MediaSessionManager {

    /** Opens a [DisplayMediaSession] for [displayId], or null if the display is not loaded. */
    override fun open(displayId: DisplayId): MediaSession? =
        DisplayManager.screens[displayId.uuid]?.let { DisplayMediaSession(it) }

    /** Sessions for every loaded display that has media assigned. */
    override fun activeSessions(): List<MediaSession> =
        DisplayManager.getScreens()
            .filter { !it.videoUrl.isNullOrEmpty() }
            .map { DisplayMediaSession(it) }
}
