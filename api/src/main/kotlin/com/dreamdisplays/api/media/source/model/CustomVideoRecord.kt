package com.dreamdisplays.api.media.source.model

import com.dreamdisplays.api.Unstable
import kotlinx.serialization.Serializable

/**
 * Custom link persisted in local link list; client-only and never sent over network.
 *
 * @since 1.9.x
 */
@Unstable
@Serializable
data class CustomVideoRecord(
    /** The normalized, playable URL. */
    val url: String,

    /** Cached display name, so the card reads the same before anything is resolved. */
    val title: String,

    /** Wall-clock time of the last use, which is what orders the list. */
    val lastUsedAtMs: Long,
)
