package com.dreamdisplays.player.events

import com.dreamdisplays.media.api.DreamMediaException

/**
 * Callbacks emitted by `MediaPlayer` to decouple playback logic from the rendering / UI layer.
 * Lambdas are invoked from the control executor unless noted otherwise.
 */
internal data class PlayerEvents(
    /** Called on an unrecoverable playback error. */
    val onError: (DreamMediaException) -> Unit,

    /** Called after a seek completes so the screen can reset its overlay state. */
    val onSeek: () -> Unit,
)
