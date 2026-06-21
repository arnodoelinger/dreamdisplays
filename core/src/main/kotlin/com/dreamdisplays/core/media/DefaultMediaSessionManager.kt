package com.dreamdisplays.core.media

import com.dreamdisplays.core.display.DisplayId
import com.dreamdisplays.core.display.DisplayService
import com.dreamdisplays.core.display.PlaybackService

/**
 * Default [MediaSessionManager] backed by the core [DisplayService] (display snapshots) and
 * [PlaybackService] (transport). Platform-agnostic: it never touches the Minecraft display objects.
 */
class DefaultMediaSessionManager(
    private val playback: PlaybackService,
    private val displays: DisplayService,
) : MediaSessionManager {

    /** Opens a [DisplayMediaSession] for [displayId], or null if the display is not known. */
    override fun open(displayId: DisplayId): MediaSession? =
        displays.getDisplay(displayId)?.let { DisplayMediaSession(displayId, playback, displays) }

    /** Sessions for every known display that has media assigned. */
    override fun activeSessions(): List<MediaSession> =
        displays.listDisplays()
            .filter { it.hasUrl }
            .map { DisplayMediaSession(it.id, playback, displays) }
}
