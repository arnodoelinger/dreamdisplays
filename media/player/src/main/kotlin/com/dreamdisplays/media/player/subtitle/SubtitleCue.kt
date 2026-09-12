package com.dreamdisplays.media.player.subtitle

/** One parsed subtitle cue: plain text shown between [startNanos] and [endNanos] of the timeline. */
data class SubtitleCue(
    val startNanos: Long,
    val endNanos: Long,
    val text: String,
)
