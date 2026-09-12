package com.dreamdisplays.media.runtime.session

import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.service.DisplayService
import com.dreamdisplays.api.media.session.service.MediaSessionService
import com.dreamdisplays.api.playback.service.PlaybackService

/**
 * Default [MediaSessionManager] backed by the core [DisplayService] (display snapshots) and
 * [PlaybackService] (transport). Platform-agnostic: it never touches the Minecraft display objects.
 */
class DefaultMediaSessionManager(
    private val playback: PlaybackService,
    private val displays: DisplayService,
) : MediaSessionManager {
    /** Opens a [DisplayMediaSession] for [displayId], or null if the display is not known. */
    override fun open(displayId: DisplayId): MediaSessionService? =
        displays.getDisplay(displayId)?.let { DisplayMediaSession(displayId, playback, displays) }

    /** Sessions for every known display that has media assigned. */
    override fun activeSessions(): List<MediaSessionService> =
        displays.listDisplays()
            .filter { it.hasUrl }
            .map { DisplayMediaSession(it.id, playback, displays) }
}
