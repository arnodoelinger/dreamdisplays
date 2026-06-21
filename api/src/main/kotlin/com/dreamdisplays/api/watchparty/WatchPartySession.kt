package com.dreamdisplays.api.watchparty

import com.dreamdisplays.api.playback.WatchPartySessionState

/**
 * A live watch-party session over a display, as seen by the local client. Ephemeral, so it exists only
 * while a party is running. Drive it through [WatchPartyService].
 *
 * @since 1.8.0
 */
data class WatchPartySession(
    /** Short server-assigned id, stable for the lifetime of this session. */
    val sessionId: String,

    /** Current lifecycle state. */
    val state: WatchPartySessionState,

    /** True if the local player hosts this session (the only one who controls playback). */
    val isHost: Boolean,

    /** Display name of the host. */
    val hostName: String,

    /** Nearby players who have marked themselves ready (during the ready-check). */
    val readyCount: Int,

    /** Total nearby players eligible to join. */
    val nearbyCount: Int,

    /** Current playback position in milliseconds. */
    val positionMs: Long,

    /** Milliseconds left in the countdown, or null when not counting down. */
    val countdownRemainingMs: Long?,
) {
    /** True while the session is counting down to its synchronized start. */
    val isCountingDown: Boolean get() = state == WatchPartySessionState.COUNTDOWN

    /** True once the session reached its frozen terminal state. */
    val isEnded: Boolean get() = state == WatchPartySessionState.ENDED
}
