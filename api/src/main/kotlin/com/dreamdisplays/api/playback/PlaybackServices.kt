package com.dreamdisplays.api.playback

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.runtime.ServiceKey
import com.dreamdisplays.api.runtime.serviceKey

/**
 * Playback service keys.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
object PlaybackServices {
    /** Public display playback command surface. */
    val PLAYBACK: ServiceKey<PlaybackService> = serviceKey("dreamdisplays:playback")
}
