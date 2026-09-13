package com.dreamdisplays.api.media.audio.service.keys

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.audio.service.AudioAcousticsService
import com.dreamdisplays.api.runtime.registry.model.ServiceKey
import com.dreamdisplays.api.runtime.registry.model.serviceKey

/**
 * Acoustics service keys.
 *
 * @since 1.9.x
 */
@Unstable
object AudioAcousticsServices {
    /** The single acoustics engine instance for the client. */
    val ACOUSTICS: ServiceKey<AudioAcousticsService> = serviceKey("dreamdisplays:audio_acoustics")
}
