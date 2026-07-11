package com.dreamdisplays.api.storage

import kotlinx.serialization.Serializable

/**
 * Persisted snapshot of one non-transient server-forced fullscreen broadcast session, enough to
 * recreate it (or its synthetic virtual display) after a server restart.
 */
@Serializable
data class FullscreenSessionRecord(
    val sessionId: String,
    val displayId: String,
    val virtual: Boolean,
    val url: String = "",
    val lang: String = "",
    val ownerId: String,
    val mode: Int,
    val forced: Boolean,
    val volume: Float,
    val loop: Boolean = false,
    val quality: String = "",
    val title: String,
    val namedTargets: List<String>? = null,
    val radiusWorld: String? = null,
    val radiusX: Double = 0.0,
    val radiusY: Double = 0.0,
    val radiusZ: Double = 0.0,
    val radiusBlocks: Double = 0.0,
)
