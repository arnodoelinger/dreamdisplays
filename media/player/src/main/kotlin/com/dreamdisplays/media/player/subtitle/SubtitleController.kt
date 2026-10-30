package com.dreamdisplays.media.player.subtitle

import com.dreamdisplays.api.media.stream.SubtitleTrack
import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.util.net.DreamHttpClient
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-viewer subtitle selection and cue cache.
 *
 * Never touches the decode pipeline.
 */
internal class SubtitleController(private val debugLabel: String, private val isTerminated: () -> Boolean) {
    private class State(
        val tracks: List<SubtitleTrack> = [],
        val track: SubtitleTrack? = null,
        val cues: List<SubtitleCue> = [],
        val enabled: Boolean = false,
    )

    @Volatile
    private var state = State()
    private var fetchId = 0

    val tracks: List<SubtitleTrack> get() = state.tracks
    val currentLang: String? get() = state.track?.lang
    val enabled: Boolean get() = state.enabled

    /** Replaces the track list after a (re-)resolve and switches subtitles off. */
    @Synchronized
    fun reset(tracks: List<SubtitleTrack>) {
        fetchId++
        state = State(tracks = tracks)
    }

    /** Selects [lang] and fetches its cues in the background, or disables subtitles when `null` or blank. */
    @Synchronized
    fun select(lang: String?) {
        val wanted = lang?.takeIf { it.isNotBlank() }
        val current = state
        if (wanted == null) {
            state = State(tracks = current.tracks)
            return
        }
        val track = current.tracks.firstOrNull { it.lang == wanted } ?: return
        if (track == current.track) {
            state = State(current.tracks, current.track, current.cues, enabled = true)
            return
        }
        state = State(current.tracks, track, [], enabled = true)
        val requestId = ++fetchId
        EXECUTOR.submit {
            val cues = runCatching {
                WebVttParser.parse(DreamHttpClient.readText(track.url))
            }.onFailure { e ->
                logger.debug("$debugLabel subtitle fetch failed for lang=$wanted: ${e.message}.")
            }.getOrDefault([])
            if (!isTerminated()) apply(requestId, cues)
        }
    }

    @Synchronized
    private fun apply(requestId: Int, cues: List<SubtitleCue>) {
        if (fetchId != requestId) return
        val current = state
        state = State(current.tracks, current.track, cues, current.enabled)
    }

    /** The cue active at [positionNanos], or `null` when subtitles are off or none is showing. */
    fun textAt(positionNanos: Long): String? {
        val current = state
        if (!current.enabled) return null
        return WebVttParser.cueAt(current.cues, positionNanos)?.text
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(SubtitleController::class.java)
        private val COUNTER = AtomicInteger()

        private val EXECUTOR: ExecutorService = Executors.newFixedThreadPool(2) { r ->
            daemon(r, "MediaPlayer-subtitles-${COUNTER.incrementAndGet()}")
        }
    }
}
