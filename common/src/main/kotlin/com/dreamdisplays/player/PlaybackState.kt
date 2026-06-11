package com.dreamdisplays.player

/**
 * Playback state of a [MediaPlayer] instance.
 *
 * @since 1.6.0
 */
enum class PlaybackState {
    /** Created but no media loaded yet. */
    IDLE,

    /** Resolving streams and spinning up the decode pipeline. */
    INITIALIZING,

    /** Actively decoding and presenting frames. */
    PLAYING,

    /** Loaded and positioned, but frame presentation is halted. */
    PAUSED,

    /** Tearing down and re-initializing the pipeline (e.g. quality change). */
    RESTARTING,

    /** Stopped by request; the pipeline is released. */
    STOPPED,

    /** Terminated by an unrecoverable playback failure. */
    ERROR,
}
