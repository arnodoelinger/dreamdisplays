package com.dreamdisplays.api.storage

import kotlinx.serialization.Serializable

/** Persisted record of one player pinning one display to their Picture-in-Picture overlay. */
@Serializable
data class PipPinRecord(
    val playerId: String,
    val displayId: String,
)
