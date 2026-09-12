package com.dreamdisplays.api.playback.model

import kotlinx.serialization.Serializable

/**
 * Persisted record of one player pinning one display to their Picture-in-Picture overlay.
 *
 * @since 1.9.x
 */
@Serializable
data class PipPinRecord(
    val playerId: String,
    val displayId: String,
)
