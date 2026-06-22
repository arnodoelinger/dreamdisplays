package com.dreamdisplays.api.media.session

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.media.DreamMediaException
import com.dreamdisplays.api.media.source.MediaMetadata
import kotlin.time.Duration

/** Events emitted by a [MediaSession] as its state, timeline, and metadata change. */
@DreamDisplaysUnstableApi sealed interface MediaSessionEvent {
    /** The session changed from [previous] to [current]. */
    data class StateChanged(
        val previous: MediaSessionState,
        val current: MediaSessionState,
    ) : MediaSessionEvent

    /** Playback position advanced or was corrected to [position]. */
    data class PositionChanged(val position: Duration) : MediaSessionEvent

    /** Playback failed with [cause]. Fatal errors usually move the session into [MediaSessionState.Error]. */
    data class Error(val cause: DreamMediaException) : MediaSessionEvent

    /** The media reached its natural end. */
    data object Ended : MediaSessionEvent

    /** Playback stalled because decode / network / buffering stopped producing frames. */
    data object Stalled : MediaSessionEvent

    /** Playback recovered after a previous [Stalled] event. */
    data object Recovered : MediaSessionEvent

    /** Rich metadata became available after session creation. */
    data class MetadataReady(val metadata: MediaMetadata) : MediaSessionEvent
}
