package com.dreamdisplays.api.storage

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import kotlinx.serialization.Serializable

/**
 * One custom link the player has played, as persisted in their local link list.
 *
 * Client-only and never sent over the wire: a pasted link exists nowhere but the message it was
 * typed into, so this is the only record of it that survives the session.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
@Serializable
data class CustomVideoRecord(
    /** The normalized, playable URL. */
    val url: String,

    /** Cached display name, so the card reads the same before anything is resolved. */
    val title: String,

    /** Wall-clock time of the last use, which is what orders the list. */
    val lastUsedAtMs: Long,
)
