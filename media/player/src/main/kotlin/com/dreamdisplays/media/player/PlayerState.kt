package com.dreamdisplays.media.player

import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.media.player.stream.ActiveStreams

/**
 * Lifecycle of a [MediaPlayer].
 *
 * Written only on the control thread; any thread may read it.
 */
internal sealed interface PlayerState {
    /** True when the UI may seek, query the position and switch tracks. */
    val isReady: Boolean get() = false

    /** Created, nothing resolved yet. */
    data object Idle : PlayerState

    /** Resolving streams on the init executor. */
    data object Preparing : PlayerState

    /** Re-resolving after a retry, stall, live resume or live quality change; the old picture still counts as ready. */
    data object Restarting : PlayerState {
        override val isReady get() = true
    }

    /** A decode session is running (possibly parked or bridging from replay). */
    data object Playing : PlayerState {
        override val isReady get() = true
    }

    /** Playback halted; the session is either parked warm or fully stopped. */
    data object Paused : PlayerState {
        override val isReady get() = true
    }

    /** A non-looping VOD reached its end; the next play starts from the beginning. */
    data object Ended : PlayerState {
        override val isReady get() = true
    }

    /** Unrecoverable failure. */
    data object Error : PlayerState

    /** Stopped for good. */
    data object Stopped : PlayerState
}

/** Streams resolved for the current media together with the quality they were selected for. */
internal data class ResolvedMedia(
    val streams: ActiveStreams,
    val live: Boolean,
    val seekable: Boolean,
    val durationNanos: Long,
    val quality: Int,
)

/** What to restore if a staged quality switch fails after the metadata was already moved to [target]. */
internal class QualityRollback(val previousStreams: ActiveStreams, val previousQuality: Int, val target: Int)

/** A quality change in flight. */
internal class QualitySwitch(val startedNanos: Long, val announced: Boolean, val rollback: QualityRollback?) {
    /** The same switch with its viewer-visible status dropped, or `null` if nothing else is left to track. */
    fun silenced(): QualitySwitch? = if (rollback == null) null else QualitySwitch(startedNanos, false, rollback)
}

/** Content aspect. */
internal fun MediaStream.contentAspect(): Double {
    val w = width ?: return 0.0
    val h = height ?: return 0.0
    return if (w > 0 && h > 0) w / h.toDouble() else 0.0
}
