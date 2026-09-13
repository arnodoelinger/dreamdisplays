package com.dreamdisplays.media.player

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.model.FramePixelFormat
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.media.audio.service.AudioDspStage
import com.dreamdisplays.api.media.player.GpuTextureRef
import com.dreamdisplays.api.media.player.PlaybackEnvironment
import com.dreamdisplays.api.media.player.PlaybackHost
import com.dreamdisplays.api.media.stream.model.MediaStream
import com.dreamdisplays.api.media.stream.model.SubtitleTrack
import com.dreamdisplays.media.player.MediaPlayer.Companion.INIT_EXECUTOR
import com.dreamdisplays.media.player.subtitle.SubtitleCue
import com.dreamdisplays.media.player.subtitle.WebVttParser
import com.dreamdisplays.util.net.DreamHttpClient
import com.dreamdisplays.media.player.events.PlayerEvents
import com.dreamdisplays.media.player.managers.PlaybackSessionManager
import com.dreamdisplays.media.player.managers.StatsReporter
import com.dreamdisplays.media.player.managers.StreamWatchdog
import com.dreamdisplays.media.player.managers.WarmTrack
import com.dreamdisplays.media.player.pipeline.PlaybackClock
import com.dreamdisplays.media.player.policy.RetryPolicy
import com.dreamdisplays.media.player.preparation.MediaPreparationService
import com.dreamdisplays.media.player.preparation.PreparedMedia
import com.dreamdisplays.media.player.process.HwAccelBackend
import com.dreamdisplays.media.player.process.MediaProcess
import com.dreamdisplays.media.player.stream.ActiveStreams
import com.dreamdisplays.media.player.stream.MediaStreamSelector
import com.dreamdisplays.media.player.util.MediaUtil
import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages media playback lifecycle: stream selection, FFmpeg, playback state, error handling.
 */
class MediaPlayer(
    private val youtubeUrl: String,
    private val lang: String,
    private val host: PlaybackHost,
    private val env: PlaybackEnvironment,
    replayBootstrap: ReplayBootstrap? = null,
    private val audioStage: AudioDspStage? = null,
) {
    /** One-shot native packet-cache bootstrap for display reappearance (includes optional audio PCM). */
    data class ReplayBootstrap(val snapshot: ByteArray, val positionNanos: Long, val audioPcm: ByteArray? = null) {
        /** Cached resolved streams for fast reappear (null = skip / re-resolve). */
        var prepared: PreparedMedia? = null
    }

    companion object {
        /** Logger. */
        private val logger = LoggerFactory.getLogger(javaClass)

        /** Debug. */
        val DEBUG: Boolean = System.getProperty("dreamdisplays.debug")?.toBoolean() == true
                || System.getenv("DREAMDISPLAYS_DEBUG").let { it == "1" || it.equals("true", ignoreCase = true) }

        /** Capture samples. */
        var captureSamples: Boolean = true

        /** Sampler counter. */
        internal val samplesIn = AtomicLong()

        /** Frames to GPU. */
        internal val framesToGpu = AtomicLong()

        /** Dropped frames. */
        internal val framesDropped = AtomicLong()

        /** Max fetch retries. */
        private const val MAX_FETCH_RETRIES = 3

        /**
         * In-place audio-half restarts allowed per session before a dead live audio escalates to a full
         * stall recovery (see [handleAudioFailure]).
         */
        private const val MAX_AUDIO_RESTARTS = 3

        private const val AUDIO_RESTART_BUDGET_RESET_NS = 120_000_000_000L

        /** Hwaccel failures show up within the first few seconds, past this window assume the stream is just unreliable. */
        private const val HWACCEL_FAIL_WINDOW_NS = 5_000_000_000L

        /** How long the "applying quality" hint may stay up before it expires on its own. */
        private const val QUALITY_STATUS_MAX_NS = 30_000_000_000L

        /**
         * A second stall within this window of the previous one means a plain restart isn't helping (most likely
         * a stale / throttled resolved URL rather than a transient hiccup), so escalate to a fresh re-resolve.
         */
        private const val REPEATED_STALL_WINDOW_NS = 90_000_000_000L

        /**
         * Audio and video are two independent `FFmpeg` processes decoding the same source, so their EOS timing can drift;
         * this guards against a premature end-of-stream near the tail.
         */
        private const val AUDIO_EOS_NEAR_END_GUARD_NS = 3_000_000_000L

        /**
         * On reappearance, cached replay resumes this far before the saved position. Default 0 = zero rewind: the saved position itself is the resume point.
         */
        private val REPLAY_LEAD_NS: Long =
            (System.getProperty("dreamdisplays.replayLeadMs")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L) * 1_000_000L

        /** Thread counter. */
        private val INIT_THREAD_COUNTER = AtomicInteger()

        /**
         * Resolve executor. Sized above the core count on purpose: this work is almost entirely
         * spent blocked on the network and on `yt-dlp`, so a pool capped at 4 made the fifth display
         * in a room wait out an earlier resolve before its own could even start.
         */
        private val INIT_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(4, 8),
        ) { r -> daemon(r, "MediaPlayer-init-${INIT_THREAD_COUNTER.incrementAndGet()}") }

        /** Shared timer for retry back-off delays, so waiting never occupies an [INIT_EXECUTOR] thread. */
        private val RETRY_SCHEDULER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r -> daemon(r, "MediaPlayer-retry") }
    }

    /** Debug label. */
    private val debugLabel = "${host.uuid}/${Integer.toHexString(System.identityHashCode(this))}"

    /** Terminated? */
    private val terminated = AtomicBoolean(false)

    /** Restart pending? (set when a stall recovery is requested while one is already in progress). */
    private val restartPending = AtomicBoolean(false)

    /** Whether viewer requested pause (tracked separately from [state] to avoid race during initialize). */
    private val pauseRequested = AtomicBoolean(false)

    /** Ended at the end of the stream? (used to avoid a stall recovery when the video side reaches EOS first). */
    private val endedAtEnd = AtomicBoolean(false)

    /** True from [changeAudioTrack] until the swap settles (promoted or gave up); drives a UI "loading" hint. */
    private val audioTrackSwitching = AtomicBoolean(false)

    /** True from [changeQuality] until the handoff settles (promoted, aborted, or restarted). */
    private val qualitySwitching = AtomicBoolean(false)

    /** When the in-flight quality switch began, so a missed settle path cannot pin the hint forever. */
    private val qualitySwitchStartedNanos = AtomicLong(0L)

    /** Clock for the current playback session; paused / parked sessions freeze the clock at the last position. */
    private val clock = PlaybackClock()

    /** Playback state. */
    private val state = AtomicReference(PlaybackState.IDLE)

    /** Replay bootstrap (if any) for fast reappear; cleared after use. */
    private val replayBootstrapRef = AtomicReference(replayBootstrap)

    /** Primed start position (if any) for the first live start; cleared after use. */
    private val primedStartPositionNanos = AtomicLong(-1L)

    /** Cached prepared streams from bootstrap (cleared after use). */
    private val preparedBootstrapRef = AtomicReference(replayBootstrap?.prepared)

    /** True once replay-only video is rendering, so [startStreams] attaches live instead of cold-starting. */
    private val replayVideoActive = AtomicBoolean(false)

    /** True when created from replay bootstrap (already positioned at saved time). */
    private val startedFromReplay = replayBootstrap != null

    /** Retry policy for stream fetches. */
    private val retryPolicy = RetryPolicy(MAX_FETCH_RETRIES)

    /** Player events. */
    private val events = PlayerEvents(
        onError = { e -> state.set(PlaybackState.ERROR); host.mediaError = e },
        onSeek = { host.afterSeek() },
    )

    /** Debug stats. */
    private val stats = StatsReporter(
        debugLabel = debugLabel,
        pollCounters = {
            StatsReporter.Snapshot(
                samplesIn.getAndSet(0),
                framesToGpu.getAndSet(0),
                framesDropped.getAndSet(0)
            )
        },
        getPositionMs = { getCurrentTime() / 1_000_000L },
        isLive = { liveStream },
    )

    /** Timestamp of last stall recovery (0 = none yet). */
    @Volatile
    private var lastStallNanos = 0L

    /** In-place audio restarts used by the current session (see [handleAudioFailure]); reset per session. */
    private val audioRestartAttempts = AtomicInteger(0)

    @Volatile
    private var lastAudioFailureNanos = 0L

    /** Guards [dispatchInitialize] so at most one resolve is ever in flight for this player. */
    private val initializing = AtomicBoolean(false)

    /** Set when a resolve was asked for while one was already running; runs exactly once afterward. */
    private val initQueued = AtomicBoolean(false)

    /** Watchdog. */
    private val watchdog = StreamWatchdog(
        debugLabel = debugLabel,
        isSessionActive = { sessionManager.isPlaying && !sessionManager.isParked() && !terminated.get() },
        getLastFrameNanos = { sessionManager.lastFrameNanos.get() },
        onStall = { handleSessionStall("no frames") },
    )

    /** Session manager. */
    private val sessionManager = PlaybackSessionManager(
        debugLabel = debugLabel,
        clock = clock,
        events = events,
        terminated = terminated,
        getTextureSize = { host.textureWidth to host.textureHeight },
        getBrightness = { brightness },
        onStreamEnd = ::handleStreamEnd,
        onQualitySwitchAborted = { appliedAnyway -> handleQualitySwitchAborted(appliedAnyway) },
        onAudioFailure = { stderr -> handleAudioFailure(stderr) },
        onAudioTrackSwitchSettled = { audioTrackSwitching.set(false) },
        renderExecutor = env.renderExecutor,
        uploaderFactory = env.uploaderFactory,
        gpuYuvActive = env.config.gpuYuvActive,
        audioStage = audioStage,
    )

    private val controlExecutor = Executors.newSingleThreadExecutor { daemon(it, "MediaPlayer-ctrl") }
    private val initCallbacks = CopyOnWriteArrayList<() -> Unit>()
    private val initDrained = AtomicBoolean(false)

    @Volatile
    private var streams: ActiveStreams? = null

    @Volatile
    private var subtitleTracks: List<SubtitleTrack> = emptyList()

    @Volatile
    private var subtitleTrack: SubtitleTrack? = null

    @Volatile
    private var subtitleCues: List<SubtitleCue> = emptyList()

    private val subtitlesEnabled = AtomicBoolean(false)

    // Bumped on every setSubtitleTrack call
    private val subtitleFetchId = AtomicInteger(0)

    @Volatile
    private var liveStream = false

    @Volatile
    private var seekable = false

    @Volatile
    private var durationHintNanos = 0L

    @Volatile
    private var lastQuality = 0

    @Volatile
    private var lastRequestedQuality = 0

    @Volatile
    private var pendingQualityRollback: QualityRollback? = null

    @Volatile
    private var brightness = 1.0

    @Volatile
    private var hwAccelDisabled = false

    @Volatile
    private var sessionStartNanos = 0L

    private val volume = VolumeController(env.config.defaultDisplayVolume) {
        sessionManager.setVolume(it)
    }

    private class QualityRollback(val previousStreams: ActiveStreams, val previousQuality: Int, val target: Int)

    init {
        // Show cached replay video immediately (network-free) so a reappearing display is instant,
        // in parallel with the live stream resolve happening on the init executor.
        replayBootstrap?.let { boot -> safeExecute { startReplayBootstrapVideo(boot) } }
        dispatchInitialize()
    }

    /** Resumes playback from the current seek position. No-op if already playing. */
    fun play() = safeExecute(::doPlay)

    /** Pauses playback, capturing the current position for later resume. */
    fun pause() = safeExecute(::doPause)

    /** True when the session can be parked warm (steady in-process-libav playback). Read from any thread. */
    fun canPark(): Boolean = isReady && sessionManager.canPark()

    /**
     * Parks the player warm while its display sits out of render distance: the decoder + audio line stay
     * open and idle (position frozen), so [unpark] resumes instantly with no re-resolve or cold-decode.
     * No-op (and the caller should fall back to a full stop) when the session is not parkable.
     */
    fun park() = safeExecute {
        watchdog.stop()
        if (!sessionManager.suspend()) watchdog.start() // Not parkable after all -> keep playing normally
    }

    /** Resumes a [park]ed player from its frozen position. */
    fun unpark() = safeExecute {
        if (sessionManager.isParked()) {
            sessionManager.resume()
            watchdog.start()
        }
    }

    /** Stops playback permanently; the instance must not be used after this call. */
    fun stop() {
        if (terminated.getAndSet(true)) return
        state.set(PlaybackState.STOPPED)
        val submitted = runCatching {
            controlExecutor.submit {
                try {
                    doStop()
                } finally {
                    controlExecutor.shutdown()
                }
            }
        }.isSuccess
        if (!submitted) {
            daemon({ doStop() }, "MediaPlayer-stop").start()
        }
    }

    /** Seeks to an absolute position in nanos. [fire] triggers [DisplayScreen.afterSeek]. */
    fun seekTo(nanos: Long, fire: Boolean) = safeExecute { doSeek(nanos, fire) }

    /** Seeks [s] seconds relative to the current position. */
    fun seekRelative(s: Double) = safeExecute {
        if (!isReady || !seekable) return@safeExecute
        val max = (getDuration() - 1).coerceAtLeast(0)
        if (max <= 0) return@safeExecute
        doSeek((getCurrentTime() + (s * 1e9).toLong()).coerceIn(0, max), true)
    }

    /** Current playback position in nanos. Falls back to the frozen / seek offset when paused or not started. */
    fun getCurrentTime(): Long {
        sessionManager.parkedPositionNanos()?.let { return it }
        if (!isReady || !sessionManager.isPlaying) return clock.originNanos
        return clock.currentTime()
    }

    /**
     * Position to save / resume from. Identical to [getCurrentTime] in normal playback, but while a replay -> live bridge
     * is active it prefers the parked or bridge-edge position instead.
     */
    fun getResumePositionNanos(): Long =
        sessionManager.parkedPositionNanos() ?: sessionManager.activeBridgeEdgeNanos() ?: getCurrentTime()

    /** Stream duration in nanos, or 0 for live streams. */
    fun getDuration(): Long = if (liveStream) 0L else durationHintNanos

    /** Primes the first live start offset before initialization opens the decoder. */
    fun primeStartPosition(nanos: Long) {
        if (nanos >= 0L && !sessionManager.isPlaying) primedStartPositionNanos.set(nanos)
    }

    /** True when this player resumed from a cached replay bootstrap (already positioned at the saved time). */
    fun isResumingFromReplay(): Boolean = startedFromReplay

    /**
     * Runs [callback] immediately if already initialized, otherwise queues it for when
     * initialization completes. The callback is called on the init thread.
     */
    fun whenInitialized(callback: () -> Unit) {
        if (initDrained.get() || isReady) {
            callback(); return
        }
        initCallbacks.add(callback)
        if ((initDrained.get() || isReady) && initCallbacks.remove(callback)) callback()
    }

    /**
     * Returns true if the stream is a live stream. Livestreams start playing immediately
     * and may not support seeking. Based on `yt-dlp` metadata; not always perfectly reliable.
     */
    fun isLive(): Boolean = liveStream

    /** Returns true if the stream supports seeking. */
    fun canSeek(): Boolean = isReady && seekable

    /** Returns true once active playback is advancing (first frame arrived, not paused / parked). */
    fun isClockRunning(): Boolean =
        sessionManager.isPlaying && !sessionManager.isParked() && clock.isRunning

    /** Connects or disconnects the popout window sink. Pass null to detach. */
    fun setPopoutSink(sink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?) {
        sessionManager.popoutFrameSink = sink
    }

    /** Connects or disconnects the display menu preview sink. Pass null to detach. */
    fun setPreviewSink(sink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?) {
        sessionManager.previewFrameSink = sink
    }

    /** True once the first decoded frame is ready for GPU upload. */
    fun textureFilled(): Boolean = sessionManager.textureFilled()

    /** Discards any ready raw frame so the renderer will not show stale content after a timeline jump. */
    fun clearFrame() = sessionManager.clearFrame()

    /**
     * Uploads latest decoded frame to texture (render thread only).
     */
    fun updateFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean =
        sessionManager.updateFrame(texture, w, h)

    /** Uploads the latest planar I420 frame into the three plane textures. Returns true if uploaded. Render thread only. */
    fun updateFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        sessionManager.updateFramePlanar(y, u, v, w, h)

    /** True while a parallel quality switch is warming up the new resolution. */
    fun hasIncomingVideo(): Boolean = sessionManager.hasIncoming()

    /** Uploads the latest incoming (quality-switch) frame to [texture]. Returns true if uploaded. Render thread only. */
    fun updateIncomingFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean =
        sessionManager.updateIncomingFrame(texture, w, h)

    /** Uploads the latest incoming planar I420 frame into the staged plane textures. Returns true if uploaded. */
    fun updateIncomingFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        sessionManager.updateIncomingFramePlanar(y, u, v, w, h)

    /** Promotes the warmed-up quality-switch channel to live; returns false if it was already aborted. */
    fun promoteIncomingVideo(): Boolean {
        val promoted = sessionManager.promoteIncoming()
        if (promoted) {
            pendingQualityRollback = null // The staged quality switch committed successfully
            qualitySwitching.set(false)
        }
        return promoted
    }

    /**
     * Reverts quality metadata if handoff fails (unblocks re-requesting same quality).
     */
    private fun handleQualitySwitchAborted(appliedAnyway: Boolean) {
        host.cancelQualityHandoff()
        qualitySwitching.set(false)
        val rollback = pendingQualityRollback
        pendingQualityRollback = null
        if (rollback == null || appliedAnyway) return
        streams = rollback.previousStreams
        lastQuality = rollback.previousQuality
        host.videoContentAspect = rollback.previousStreams.currentVideo.contentAspect()
        if (lastRequestedQuality == rollback.target) lastRequestedQuality = rollback.previousQuality
    }

    /** Sets the user-controlled volume (0.0–2.0). Distance attenuation is applied on top. */
    fun setVolume(volume: Float) = this.volume.setUserVolume(volume)

    /**
     * Seeds volume up-front (before audio starts) to avoid loud burst on reappear.
     */
    fun primeVolume(userVolume: Float, distance: Double, maxRadius: Double) {
        volume.setUserVolume(userVolume)
        volume.updateAttenuation(distance, maxRadius)
    }

    /** Sets the brightness multiplier applied to each frame before GPU upload (0.0–2.0). */
    fun setBrightness(brightness: Float) {
        this.brightness = brightness.toDouble().coerceIn(0.0, 2.0)
    }

    /** Returns the list of available video quality levels (in pixels) for the current stream. */
    fun getAvailableQualities(): List<Int> {
        val cap = if (env.config.isPremium) 2160 else 1080
        return streams?.availableVideo.orEmpty().asSequence()
            .mapNotNull { it.height }
            .filter { it <= cap }
            .distinct().sorted().toList()
    }

    /**
     * Switches to [quality]. [userInitiated] only controls whether the change is worth reporting through
     * [isApplyingQuality]: the automatic distance ladder and settings restores re-push quality on their
     * own and must stay silent, since the viewer never asked for anything.
     */
    fun setQuality(quality: VideoQuality, userInitiated: Boolean = false) =
        safeExecute { changeQuality(quality, userInitiated) }

    /**
     * Returns selectable audio tracks (deduped by language).
     */
    fun getAvailableAudioTracks(): List<MediaStream> {
        val audio = streams?.availableAudio?.filter { !it.type.hasVideo } ?: return emptyList()
        return audio
            .groupBy { it.audioTrackLang ?: it.audioTrackName }
            .values
            .map { group -> group.maxByOrNull { it.bitrate ?: 0 } ?: group.first() }
    }

    /**
     * URL of currently-playing audio track for UI highlighting (null before stream resolves).
     */
    fun getCurrentAudioTrack(): String? {
        val current = streams?.currentAudio ?: return null
        val key = current.audioTrackLang ?: current.audioTrackName
        return getAvailableAudioTracks()
            .firstOrNull { (it.audioTrackLang ?: it.audioTrackName) == key }?.url
            ?: current.url
    }

    /** Switches the active audio track to the one identified by [trackUrl]. */
    fun setAudioTrack(trackUrl: String) = safeExecute { changeAudioTrack(trackUrl) }

    /** True while a [setAudioTrack] switch is in flight; the actual audio can lag the UI selection by a few seconds. */
    fun isSwitchingAudioTrack(): Boolean = audioTrackSwitching.get()

    /** Selectable subtitle tracks for the current video (empty when the source exposed none). */
    fun getAvailableSubtitleTracks(): List<SubtitleTrack> = subtitleTracks

    /** Language of the currently selected subtitle track, or null when subtitles are off. */
    fun getCurrentSubtitleLang(): String? = subtitleTrack?.lang

    /** True while subtitles are enabled for this viewer, regardless of whether a cue is showing right now. */
    fun isSubtitlesEnabled(): Boolean = subtitlesEnabled.get()

    /**
     * Selects the subtitle track by [lang] and enables display, or disables subtitles when [lang] is
     * null /b lank. Fetches and parses the track's WebVTT file on a background thread; per-viewer only,
     * never touches the decode pipeline.
     */
    fun setSubtitleTrack(lang: String?) {
        val wanted = lang?.takeIf { it.isNotBlank() }
        if (wanted == null) {
            subtitlesEnabled.set(false)
            subtitleTrack = null
            subtitleCues = emptyList()
            return
        }
        val track = subtitleTracks.firstOrNull { it.lang == wanted } ?: return
        subtitlesEnabled.set(true)
        if (track == subtitleTrack) return
        subtitleTrack = track
        subtitleCues = emptyList()
        val requestId = subtitleFetchId.incrementAndGet()
        INIT_EXECUTOR.submit {
            val cues = runCatching {
                WebVttParser.parse(DreamHttpClient.readText(track.url))
            }.onFailure { e ->
                logger.debug("$debugLabel subtitle fetch failed for lang=$wanted: ${e.message}")
            }.getOrDefault(emptyList())
            if (!terminated.get() && subtitleFetchId.get() == requestId) subtitleCues = cues
        }
    }

    /** Current subtitle line for [getCurrentTime], or null when subtitles are off or no cue is active. */
    fun getCurrentSubtitleText(): String? {
        if (!subtitlesEnabled.get()) return null
        return WebVttParser.cueAt(subtitleCues, getCurrentTime())?.text
    }

    /**
     * True while a [setQuality] change is still being applied: the new resolution decodes in a second
     * channel and only replaces the picture once its first frame lands, so the switch outlives the click.
     */
    fun isApplyingQuality(): Boolean {
        if (!qualitySwitching.get()) return false
        // Every settle path below clears the flag, but a hint stuck on forever would be worse than one
        // that disappears early, so it also expires on its own.
        if (System.nanoTime() - qualitySwitchStartedNanos.get() > QUALITY_STATUS_MAX_NS) {
            qualitySwitching.set(false)
            return false
        }
        return true
    }

    /** Marks a quality change as in flight (see [isApplyingQuality]). */
    private fun beginQualityStatus() {
        qualitySwitchStartedNanos.set(System.nanoTime())
        qualitySwitching.set(true)
    }

    /** Reopens the current stream without changing URL/quality; used when render backend requirements change. */
    fun restartVideoPipeline() = safeExecute {
        val ss = streams ?: return@safeExecute
        if (isPausedWarm()) freezePausedWarmSession()
        val pos = if (liveStream) 0L else getCurrentTime()
        env.renderExecutor.execute {
            host.reloadTexture()
            safeExecute {
                if (sessionManager.isPlaying && !sessionManager.isParked()) startStreams(ss, pos)
                else clock.moveTo(pos)
            }
        }
    }

    /** Captures the active native LAV packet-ring snapshot (whole window), if the live channel has one. */
    fun captureReplaySnapshot(): ByteArray? = sessionManager.captureVideoCacheSnapshot()

    /** Captures the recent PCM window (matching the replay video lead) for the reappearance audio bridge. */
    fun captureReplayAudio(): ByteArray? = sessionManager.captureAudioPcm(REPLAY_LEAD_NS)

    /**
     * Captures resolved streams for fast reappear (null for live streams or before init).
     */
    fun capturePreparedMedia(): PreparedMedia? {
        if (liveStream) return null
        val ss = streams ?: return null
        return PreparedMedia(ss, liveStream, seekable, durationHintNanos)
    }

    /**
     * Raw stream URL for scrub-preview extraction (null for live or unresolved).
     */
    fun capturedStreamRawUrl(): String? = capturePreparedMedia()?.streamSet?.currentVideo?.url

    /**
     * Whether captured stream uses decode-forward seek path.
     */
    fun capturedStreamSeeksByDecoding(): Boolean =
        capturePreparedMedia()?.streamSet?.currentVideo?.seekByDecoding == true

    /**
     * Updates distance-based volume attenuation (call every tick from game thread).
     */
    fun tick(distance: Double, maxRadius: Double) {
        if (!isReady) return
        volume.updateAttenuation(distance, maxRadius)
    }

    /**
     * Submits initialize to [INIT_EXECUTOR], guarded so only one resolve is in flight.
     */
    private fun dispatchInitialize(coalesce: Boolean = false) {
        if (terminated.get()) return
        if (!initializing.compareAndSet(false, true)) {
            if (coalesce) initQueued.set(true)
            return
        }
        INIT_EXECUTOR.submit {
            try {
                if (!terminated.get()) initialize()
            } finally {
                initializing.set(false)
                if (initQueued.compareAndSet(true, false)) dispatchInitialize()
            }
        }
    }

    /**
     * Resolves stream, updates metadata, fires callbacks (or marks error).
     */
    private fun initialize() {
        state.set(PlaybackState.INITIALIZING)
        runCatching {
            val prepared = preparedBootstrapRef.getAndSet(null)
                ?: MediaPreparationService.prepare(youtubeUrl, lang, host.quality, env)

            if (terminated.get()) return

            prepared.also {
                liveStream = it.isLive
                seekable = it.isSeekable
                durationHintNanos = it.durationNanos
                streams = it.streamSet
                lastQuality = MediaStreamSelector.parseQuality(it.streamSet.currentVideo)
                host.videoContentAspect = it.streamSet.currentVideo.contentAspect()
                subtitleTracks = it.availableSubtitles
                subtitleTrack = null
                subtitleCues = emptyList()
                subtitlesEnabled.set(false)
            }

            if (DEBUG) {
                logger.debug(
                    "{} video={} audio={}",
                    debugLabel,
                    prepared.streamSet.currentVideo,
                    prepared.streamSet.currentAudio
                )
                logger.debug("$debugLabel live=$liveStream seekable=$seekable dur=$durationHintNanos")
                stats.start()
            }

            val primed = primedStartPositionNanos.get().takeIf { it >= 0L } ?: 0L
            val initialOffset = replayBootstrapRef.get()?.positionNanos ?: primed

            safeExecute { if (!terminated.get()) startStreams(prepared.streamSet, initialOffset) }
        }.onSuccess {
            drainInitCallbacks(run = true)
        }.onFailure { e ->
            logger.error("$debugLabel Initialization failed: ${e.message}.")
            state.set(PlaybackState.ERROR)
            host.mediaError = e as? DreamMediaException
                ?: DreamMediaException.Unknown(
                    e.message ?: "initialization failed",
                    e
                )
            drainInitCallbacks(run = false)
        }
    }

    /**
     * Starts session manager and watchdog (control executor thread only).
     */
    private fun startStreams(streamSet: ActiveStreams, offsetNanos: Long) {
        if (terminated.get()) return
        if (pauseRequested.get()) {
            // Paused while this was being resolved: honor that rather than starting under the viewer
            logger.debug("$debugLabel Start skipped: playback was paused while the media was being prepared.")
            state.set(PlaybackState.PAUSED)
            return
        }
        endedAtEnd.set(false)
        // Replay-only video may already be on screen (started at construction): attach the live source
        // and hand off by PTS instead of cold-starting, so the picture never blanks.
        val bootstrap = replayBootstrapRef.getAndSet(null)
        logger.debug(
            "$debugLabel [reappear] startStreams offset=${"%.1f".format(offsetNanos / 1_000_000.0)}ms " +
                    "replayActive=${replayVideoActive.get()} bootstrap=${bootstrap != null} live=$liveStream",
        )
        if (replayVideoActive.get() && bootstrap != null && !liveStream) {
            if (attachLiveToReplay(streamSet, bootstrap.positionNanos)) return
            logger.debug("$debugLabel [reappear] attachLiveToReplay failed; falling back to cold start.")
            replayVideoActive.set(false) // Attach failed: fall through to a normal cold start
        }
        // A full restart decodes at the current texture's dimensions, so any staged quality handoff
        // (which expects new dimensions) would never match and must be dropped to avoid a frozen frame.
        host.cancelQualityHandoff()
        sessionStartNanos = System.nanoTime()
        audioRestartAttempts.set(0)
        lastAudioFailureNanos = 0L
        sessionManager.start(
            streamSet,
            offsetNanos,
            lastQuality,
            currentHwAccel(),
            live = liveStream,
            onFirstFrame = retryPolicy::reset
        )
        if (sessionManager.isPlaying) {
            state.set(PlaybackState.PLAYING)
            watchdog.start()
            refreshWarmAudioTracks()
            // Settles the live quality path, which applies by restarting the whole session.
            qualitySwitching.set(false)
        }
    }

    /**
     * Starts cached replay video instantly (no network, no audio).
     */
    private fun startReplayBootstrapVideo(boot: ReplayBootstrap) {
        if (terminated.get()) return
        val resume = (boot.positionNanos - REPLAY_LEAD_NS).coerceAtLeast(0L)
        if (sessionManager.startReplayVideoOnly(boot.snapshot, resume, boot.positionNanos, boot.audioPcm)) {
            replayVideoActive.set(true)
            state.set(PlaybackState.PLAYING)
            logger.debug("$debugLabel Replay bootstrap shown instantly, resuming at ${"%.1f".format(resume / 1_000_000.0)}ms.")
        }
        // On failure the bootstrap is left in place (replayVideoActive stays false): startStreams then
        // cold-starts at the saved position instead of attaching live to a replay that never started.
    }

    /**
     * Attaches live stream while replay video plays (false if attachment fails).
     */
    private fun attachLiveToReplay(streamSet: ActiveStreams, liveOffsetNanos: Long): Boolean {
        env.renderExecutor.execute { host.beginQualityHandoff() }
        sessionStartNanos = System.nanoTime()
        if (!sessionManager.attachLiveAfterReplay(streamSet, liveOffsetNanos, lastQuality, currentHwAccel())) {
            env.renderExecutor.execute { host.cancelQualityHandoff() }
            return false
        }
        state.set(PlaybackState.PLAYING)
        watchdog.start()
        logger.debug("$debugLabel Attached live after replay at ${"%.1f".format(liveOffsetNanos / 1_000_000.0)}ms.")
        return true
    }

    /**
     * Warms new-quality video as parallel channel (seamless switch).
     */
    private fun beginQualitySwitch(
        streamSet: ActiveStreams,
        offsetNanos: Long,
        hwAccelOverride: HwAccelBackend? = null,
    ) {
        if (terminated.get()) return
        sessionManager.beginQualitySwitch(streamSet, offsetNanos, lastQuality, hwAccelOverride ?: currentHwAccel())
    }

    /** The hardware decode backend for new sessions, honoring config and the per-stream software fallback. */
    private fun currentHwAccel(): HwAccelBackend =
        if (env.config.useHwAccel && !hwAccelDisabled) HwAccelBackend.detectDefault() else HwAccelBackend.NONE

    /**
     * Stops watchdog and session.
     */
    private fun stopSession() {
        watchdog.stop()
        sessionManager.stop()
    }

    /**
     * Handles stream end: retry, loop VOD, or mark error.
     */
    private fun handleStreamEnd(stderr: String, normalEos: Boolean) {
        if (terminated.get()) return
        if (!hwAccelDisabled && !normalEos && !clock.isRunning
            && System.nanoTime() - sessionStartNanos < HWACCEL_FAIL_WINDOW_NS
            && HwAccelBackend.looksLikeHwAccelFailure(stderr)
        ) {
            hwAccelDisabled = true
            logger.warn(
                "$debugLabel Hardware decode failed for this stream. Falling back to software. Stderr: ${
                    MediaUtil.truncate(
                        stderr
                    )
                }."
            )
            val ss = streams
            if (ss != null) safeExecute { if (!terminated.get()) startStreams(ss, 0) }
            return
        }

        val decision = retryPolicy.evaluate(stderr, normalEos, liveStream)
        if (decision != null) {
            scheduleRetry(decision.invalidateCache)
            return
        }

        if (normalEos && !liveStream) {
            if (host.shouldLoopOnEnd) {
                restartFromBeginning()
            } else {
                host.onPlaybackEnded(durationHintNanos)
            }
            return
        }

        if (stderr.isNotEmpty()) {
            logger.error("$debugLabel Unrecoverable: ${MediaUtil.truncate(stderr)}.")
        }
        state.set(PlaybackState.ERROR)
        host.mediaError = DreamMediaException.Decode("Unrecoverable stream failure", isFatal = true)
    }

    /**
     * Loops VOD playback after normal end.
     */
    private fun restartFromBeginning() {
        if (!restartPending.compareAndSet(false, true)) return
        safeExecute {
            try {
                val ss = streams
                if (ss != null && !terminated.get() && !host.isPaused) {
                    endedAtEnd.set(false)
                    if (!sessionManager.beginSeek(ss, 0, lastQuality, currentHwAccel())) {
                        clock.reset(0)
                        startStreams(ss, 0)
                    }
                    events.onSeek()
                }
            } finally {
                restartPending.set(false)
            }
        }
    }

    /**
     * Schedules re-initialization after exponential back-off delay.
     */
    private fun scheduleRetry(invalidateCache: Boolean) {
        val delayMs = retryPolicy.nextDelay()
        logger.warn("$debugLabel ${if (invalidateCache) "Cache invalidated" else "Transient error"}. Retry ${retryPolicy.retries}/$MAX_FETCH_RETRIES in $delayMs ms.")
        if (invalidateCache) {
            env.cacheInvalidator.invalidate(youtubeUrl)
            forgetResolvedStreamUrls()
        }
        state.set(PlaybackState.RESTARTING)
        RETRY_SCHEDULER.schedule({ dispatchInitialize() }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Handles audio end-of-stream: defers if near VOD end, restarts audio on live, escalates to stall.
     */
    private fun handleAudioFailure(stderr: String) {
        // A source with no audio track at all is not a failure to recover from: restarting it only
        // produces the same empty output, which is how a silent video used to lock the player into an
        // endless invalidate-and-re-resolve loop. Play it silently and stop spawning audio for it.
        if (MediaProcess.indicatesNoAudioStream(stderr)) {
            val silentUrl = streams?.currentAudio?.url
            if (silentUrl == null || sessionManager.markSourceSilent(silentUrl)) {
                logger.info("$debugLabel This source carries no audio track; playing it silently.")
            }
            return
        }
        if (!liveStream && durationHintNanos > 0L && durationHintNanos - clock.currentTime() <= AUDIO_EOS_NEAR_END_GUARD_NS) {
            logger.debug("$debugLabel Audio pipe ended near VOD end (pos=${clock.currentTime()}, dur=$durationHintNanos); deferring to video EOS.")
            return
        }
        if (liveStream && sessionManager.audioSourceGone()) {
            handleSessionStall("live audio source stopped serving")
            return
        }
        val now = System.nanoTime()
        if (lastAudioFailureNanos != 0L && now - lastAudioFailureNanos > AUDIO_RESTART_BUDGET_RESET_NS) {
            audioRestartAttempts.set(0)
        }
        lastAudioFailureNanos = now
        val attempt = audioRestartAttempts.incrementAndGet()
        if (liveStream && sessionManager.isPlaying && attempt <= MAX_AUDIO_RESTARTS) {
            logger.warn(
                "$debugLabel Live audio ended (${MediaUtil.truncate(stderr)}); " +
                        "restarting audio only ($attempt/$MAX_AUDIO_RESTARTS), video keeps playing."
            )
            RETRY_SCHEDULER.schedule({
                safeExecute {
                    val ss = streams
                    if (terminated.get() || ss == null) return@safeExecute
                    if (!sessionManager.restartAudio(ss, 0L)) {
                        handleSessionStall("audio restart not possible in current session state")
                    }
                }
            }, attempt * 1_000L, TimeUnit.MILLISECONDS)
            return
        }
        handleSessionStall("audio ended: ${MediaUtil.truncate(stderr)}.")
    }

    /**
     * Recovers from stalled session: first stall retries same streams, second escalates to re-resolve (live immediately).
     */
    private fun handleSessionStall(reason: String) {
        if (terminated.get()) return
        val ss = streams ?: return
        val now = System.nanoTime()
        val repeated = lastStallNanos != 0L && now - lastStallNanos < REPEATED_STALL_WINDOW_NS
        lastStallNanos = now
        if (repeated || liveStream) {
            val kind = if (liveStream) "Live stall" else "Repeated stall"
            logger.warn("$debugLabel $kind ($reason); invalidating cached URLs and re-resolving.")
            env.cacheInvalidator.invalidate(youtubeUrl)
            forgetResolvedStreamUrls()
            primedStartPositionNanos.set(if (liveStream) 0L else clock.currentTime())
            state.set(PlaybackState.RESTARTING)
            dispatchInitialize()
        } else {
            logger.warn("$debugLabel Stream stalled ($reason); restarting.")
            safeExecute {
                val pos = if (liveStream) 0L else clock.currentTime()
                // Restart in place when possible: the picture holds its last frame while the new
                // session connects, instead of blanking through a blocking teardown.
                if (sessionManager.beginSeek(ss, pos, lastQuality, currentHwAccel())) {
                    // The watchdog stops itself when it reports a stall, and this path never goes
                    // through startStreams, so nothing else would ever watch this session again.
                    watchdog.start()
                } else {
                    startStreams(ss, pos)
                }
            }
        }
    }

    /**
     * Invalidates SSRF guard memo of stream URL redirects (for re-resolve).
     */
    private fun forgetResolvedStreamUrls() {
        val ss = streams ?: return
        MediaHostGuard.invalidate(ss.currentVideo.url)
        MediaHostGuard.invalidate(ss.currentAudio.url)
    }

    /** Starts `FFmpeg` from the current seek offset. No-op if already playing or not ready. */
    private fun doPlay() {
        if (!isReady || terminated.get()) return
        pauseRequested.set(false)
        if (isPausedWarm()) {
            sessionManager.resume()
            state.set(PlaybackState.PLAYING)
            watchdog.start()
            return
        }
        if (sessionManager.isPlaying) return
        val ss = streams ?: return
        if (liveStream) {
            logger.debug("$debugLabel Live resume from cold pause; re-resolving playlist URLs.")
            // A live playlist captured before the pause points at segments the server has already
            // rotated away, so re-initializing on the cached resolve just hands FFmpeg dead URLs: it
            // then spends its reconnect budget failing, trips the stall path, and only *then* does
            // the re-resolve this branch always claimed to do — turning a resume into a minute of
            // black screen. Drop the memo first so the very first attempt is a live one.
            env.cacheInvalidator.invalidate(youtubeUrl)
            forgetResolvedStreamUrls()
            endedAtEnd.set(false)
            primedStartPositionNanos.set(0L)
            state.set(PlaybackState.RESTARTING)
            dispatchInitialize(coalesce = true)
            return
        }
        val offset = if (endedAtEnd.getAndSet(false)) 0L else clock.originNanos
        startStreams(ss, offset)
    }

    /**
     * Pauses playback (warm pause on VOD, cold pause on live).
     */
    private fun doPause() {
        pauseRequested.set(true)
        if (!sessionManager.isPlaying) return
        if (!liveStream) {
            watchdog.stop()
            if (sessionManager.suspend(allowExternalProcess = true, retainBuffered = true)) {
                state.set(PlaybackState.PAUSED)
                return
            }
        }
        val heard = sessionManager.currentPacingNanos()
        clock.reset(
            when {
                liveStream -> 0L
                heard >= 0L -> heard
                else -> clock.currentTime()
            }
        )
        state.set(PlaybackState.PAUSED)
        stopSession()
    }

    /**
     * Full teardown: clears the frame buffer, stops stats, stops the session, releases GPU
     * resources (PBOs), and nulls [streams].
     */
    private fun doStop() {
        sessionManager.clearFrame()
        stats.stop()
        stopSession()
        sessionManager.cleanup()
        streams = null
    }

    /**
     * Moves the seek offset to [nanos]. While playing this is an in-place seek: the picture freezes on its last frame until
     * the decoder resumes past the new position.
     */
    private fun doSeek(nanos: Long, fire: Boolean) {
        if (!isReady || !seekable) return
        endedAtEnd.set(false)
        if (isPausedWarm()) freezePausedWarmSession()
        // Park the clock at the target right away so the UI reads the seeked position, and so no
        // pipe can pace one more frame against a half-updated timeline while the seek is set up.
        clock.reset(nanos)
        val ss = streams ?: return
        if (sessionManager.isPlaying && !sessionManager.isParked()) {
            if (!sessionManager.beginSeek(ss, nanos, lastQuality, currentHwAccel())) {
                logger.warn("$debugLabel Seek to ${nanos / 1_000_000} ms fell back to a full stream restart.")
                startStreams(ss, nanos)
            }
        }
        if (fire) events.onSeek()
    }

    /**
     * Picks the closest available stream to [desired] quality. Updates [streams] via copy
     * and restarts `FFmpeg` when playing, or repositions seek offset when paused.
     */
    private fun changeQuality(desired: VideoQuality, userInitiated: Boolean) {
        val ss = streams ?: return
        val target = desired.targetHeight ?: return
        if (target == lastQuality || target == lastRequestedQuality) {
            if (DEBUG) logger.debug(
                "$debugLabel Quality switch no-op target=$target last=$lastQuality requested=$lastRequestedQuality."
            )
            return
        }
        lastRequestedQuality = target
        if (liveStream) {
            logger.debug("$debugLabel Live quality switch to ${target}p; re-resolving and restarting.")
            // Cleared once the restarted session reaches [startStreams].
            if (userInitiated) beginQualityStatus()
            primedStartPositionNanos.set(0L)
            state.set(PlaybackState.RESTARTING)
            dispatchInitialize(coalesce = true)
            return
        }
        if (isPausedWarm()) freezePausedWarmSession()
        val newSs = MediaStreamSelector.switchQuality(ss, target) ?: return
        val previousQuality = lastQuality
        streams = newSs
        lastQuality = MediaStreamSelector.parseQuality(newSs.currentVideo)
        host.videoContentAspect = newSs.currentVideo.contentAspect()
        env.renderExecutor.execute {
            if (sessionManager.isPlaying && !sessionManager.isParked()) {
                // Parallel quality switch: stage the new-resolution texture, but the live video keeps
                // decoding and rendering. The new resolution warms up in a second channel; fitTexture
                // promotes both (channel + texture) on its first frame, so the picture never freezes.
                // A genuine handoff failure rolls the metadata above back (see handleQualitySwitchAborted).
                pendingQualityRollback = QualityRollback(ss, previousQuality, target)
                // Announced only here: a real handoff outlives the click by seconds, whereas the direct
                // swap below finishes inside this very block, so flagging it would only ever flicker.
                if (userInitiated) beginQualityStatus()
                host.beginQualityHandoff()
                safeExecute { beginQualitySwitch(newSs, getCurrentTime()) }
            } else {
                // Nothing decoding (so, just paused): no frames would arrive to drive a handoff, so swap
                // directly — there's no async attempt in flight to roll back if this fails later.
                pendingQualityRollback = null
                host.reloadTexture()
                safeExecute { clock.moveTo(getCurrentTime()) }
                // A direct swap is done the moment it returns: there is no handoff to wait out.
                qualitySwitching.set(false)
            }
        }
    }

    /**
     * Swaps only the audio channel to the track identified by [trackUrl], leaving the video, clock, and picture untouched
     * (audio-only respawn).
     */
    private fun changeAudioTrack(trackUrl: String) {
        val ss = streams ?: return
        if (trackUrl == ss.currentAudio.url) return
        val newSs = MediaStreamSelector.switchAudioTrack(ss, trackUrl) ?: return
        streams = newSs
        audioTrackSwitching.set(true)
        val seamless = sessionManager.beginAudioTrackSwitch(newSs)
        refreshWarmAudioTracks()
        if (seamless) return
        env.renderExecutor.execute {
            safeExecute { sessionManager.restartAudio(newSs, getCurrentTime()) }
            audioTrackSwitching.set(false)
        }
    }

    /**
     * Re-declares which dubs the session keeps pre-warmed: every selectable track except the playing
     * one. Live sources are excluded — they have no seekable spawn position a shadow could hold.
     */
    private fun refreshWarmAudioTracks() {
        val ss = streams
        if (ss == null || liveStream) {
            sessionManager.setWarmAudioTracks(emptyList())
            return
        }
        val playing = ss.currentAudio.url
        sessionManager.setWarmAudioTracks(
            getAvailableAudioTracks()
                .filter { it.url != playing }
                .map { WarmTrack(it.url, it.seekByDecoding) },
        )
    }

    /** Is warm session paused? */
    private fun isPausedWarm(): Boolean =
        state.get() == PlaybackState.PAUSED && sessionManager.isParked()

    /**
     * Converts a warm-paused session back to the ordinary paused representation before operations that
     * need a cold restart later (seek, quality / backend switch). This preserves pause semantics instead of
     * accidentally starting decode while the UI still says paused.
     */
    private fun freezePausedWarmSession() {
        val pos = sessionManager.parkedPositionNanos() ?: getCurrentTime()
        stopSession()
        clock.reset(pos)
        state.set(PlaybackState.PAUSED)
    }

    private fun MediaStream.contentAspect(): Double {
        val w = width ?: return 0.0
        val h = height ?: return 0.0
        return if (w > 0 && h > 0) w / h.toDouble() else 0.0
    }

    /**
     * Drains [initCallbacks] and invokes each callback when [run] is true. Marks the drain done
     * first, so a callback registered concurrently either lands in the list before it empties or
     * runs immediately in [whenInitialized] — never both, never neither.
     */
    private fun drainInitCallbacks(run: Boolean) {
        if (run) initDrained.set(true)
        // Remove one at a time by identity so a callback added mid-drain is never wiped without running
        while (true) {
            val cb = initCallbacks.firstOrNull() ?: break
            if (initCallbacks.remove(cb) && run) cb()
        }
    }

    /** Submits [action] to the control executor if the player is not terminated. */
    private fun safeExecute(action: () -> Unit) {
        if (!terminated.get() && !controlExecutor.isShutdown)
            runCatching { controlExecutor.submit(action) }
    }

    /** True when the player is in a state where playback operations are valid. */
    private val isReady: Boolean
        get() = state.get()
            .let { it == PlaybackState.PLAYING || it == PlaybackState.PAUSED || it == PlaybackState.RESTARTING }
}
