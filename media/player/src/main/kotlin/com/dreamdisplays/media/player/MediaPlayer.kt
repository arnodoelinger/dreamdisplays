package com.dreamdisplays.media.player

import com.dreamdisplays.api.media.audio.AudioDspStage
import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.model.FramePixelFormat
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.media.player.GpuTextureRef
import com.dreamdisplays.api.media.player.PlaybackEnvironment
import com.dreamdisplays.api.media.player.PlaybackHost
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.SubtitleTrack
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
import com.dreamdisplays.media.player.subtitle.SubtitleController
import com.dreamdisplays.media.player.util.MediaUtil
import com.dreamdisplays.media.player.util.MediaUtil.ms
import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Manages media playback lifecycle: stream selection, `FFmpeg`, playback state, error handling. */
class MediaPlayer(
    private val url: String,
    private val lang: String,
    private val host: PlaybackHost,
    private val env: PlaybackEnvironment,
    @Volatile private var replayBootstrap: ReplayBootstrap? = null,
    private val audioStage: AudioDspStage? = null,
) {
    private val debugLabel = "${host.uuid}/${Integer.toHexString(System.identityHashCode(this))}"
    private val terminated = AtomicBoolean(false)
    private val clock = PlaybackClock()
    private val startedFromReplay = replayBootstrap != null
    private val retryPolicy = RetryPolicy(MAX_FETCH_RETRIES)

    @Volatile
    private var lifecycle: PlayerState = PlayerState.Idle

    @Volatile
    private var media: ResolvedMedia? = null

    @Volatile
    private var qualitySwitch: QualitySwitch? = null

    @Volatile
    private var audioTrackSwitching = false

    private var pauseRequested = false
    private var restartPending = false
    private var hwAccelDisabled = false
    private var lastRequestedQuality = 0
    private var sessionStartNanos = 0L
    private var sessionStartOffsetNanos = 0L
    private var lastStallNanos = 0L
    private var stallReResolves = 0
    private var audioRestartAttempts = 0
    private var lastAudioFailureNanos = 0L

    private var preparedBootstrap: PreparedMedia? = replayBootstrap?.prepared

    private val state: PlayerState get() = if (terminated.get()) PlayerState.Stopped else lifecycle

    private val isReady: Boolean get() = state.isReady

    @Volatile
    private var primedStartPositionNanos = -1L

    private val events = PlayerEvents(
        onError = { e -> onControl { lifecycle = PlayerState.Error }; host.mediaError = e },
        onSeek = { host.afterSeek() },
    )

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

    private enum class InitGate { IDLE, RUNNING, RUNNING_QUEUED }

    private val initGate = AtomicReference(InitGate.IDLE)

    private val watchdog = StreamWatchdog(
        debugLabel = debugLabel,
        isSessionActive = { sessionManager.isPlaying && !sessionManager.isParked() && !terminated.get() },
        getLastFrameNanos = { sessionManager.lastFrameNanos.get() },
        onStall = { handleSessionStall("no frames") },
    )

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
        onAudioTrackSwitchSettled = { onControl { audioTrackSwitching = false } },
        renderExecutor = env.renderExecutor,
        uploaderFactory = env.uploaderFactory,
        gpuYuvActive = env.config.gpuYuvActive,
        audioStage = audioStage,
    )

    @Volatile
    private var controlThread: Thread? = null
    private val controlExecutor = Executors.newSingleThreadExecutor { r ->
        daemon(r, "MediaPlayer-ctrl").also { controlThread = it }
    }

    private val initCallbacks = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var initDrained = false

    private val subtitles = SubtitleController(debugLabel) { terminated.get() }

    private var streams: ActiveStreams?
        get() = media?.streams
        set(value) {
            media = if (value == null) null else media?.copy(streams = value)
        }

    private var lastQuality: Int
        get() = media?.quality ?: 0
        set(value) {
            media = media?.copy(quality = value)
        }

    private val liveStream: Boolean get() = media?.live == true
    private val seekable: Boolean get() = media?.seekable == true
    private val durationHintNanos: Long get() = media?.durationNanos ?: 0L

    @Volatile
    private var brightness = 1.0

    private val volume = VolumeController(env.config.defaultDisplayVolume) {
        sessionManager.setVolume(it)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(javaClass)

        val DEBUG: Boolean = System.getProperty("dreamdisplays.debug")?.toBoolean() == true
                || System.getenv("DREAMDISPLAYS_DEBUG").let { it == "1" || it.equals("true", ignoreCase = true) }

        var captureSamples: Boolean = true

        internal val samplesIn = AtomicLong()
        internal val framesToGpu = AtomicLong()
        internal val framesDropped = AtomicLong()

        private const val MAX_FETCH_RETRIES = 3
        private const val MAX_AUDIO_RESTARTS = 3
        private const val AUDIO_RESTART_BUDGET_RESET_NS = 120_000_000_000L
        private const val HWACCEL_FAIL_WINDOW_NS = 5_000_000_000L
        private const val QUALITY_STATUS_MAX_NS = 30_000_000_000L
        private const val REPEATED_STALL_WINDOW_NS = 90_000_000_000L
        private val STALL_RERESOLVE_BACKOFF_MS = longArrayOf(0L, 2_000L, 5_000L, 15_000L, 30_000L)
        private const val MAX_STALL_RERESOLVES = 6
        private const val AUDIO_EOS_NEAR_END_GUARD_NS = 3_000_000_000L

        private val INIT_THREAD_COUNTER = AtomicInteger()

        private val INIT_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(4, 8),
        ) { r -> daemon(r, "MediaPlayer-init-${INIT_THREAD_COUNTER.incrementAndGet()}") }

        private val RETRY_SCHEDULER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r -> daemon(r, "MediaPlayer-retry") }
    }

    /** One-shot native packet-cache bootstrap for display reappearance. */
    class ReplayBootstrap(val snapshot: ByteArray, val positionNanos: Long) {
        var prepared: PreparedMedia? = null
    }

    init {
        this.replayBootstrap?.let { boot -> safeExecute { startReplayBootstrapVideo(boot) } }
        dispatchInitialize()
    }

    /** Resumes playback from the current seek position. No-op if already playing. */
    fun play() = safeExecute {
        if (!isReady || terminated.get()) return@safeExecute
        pauseRequested = false
        if (isPausedWarm()) {
            sessionManager.resume()
            lifecycle = PlayerState.Playing
            watchdog.start()
            return@safeExecute
        }
        if (sessionManager.isPlaying) return@safeExecute
        val ss = streams ?: return@safeExecute
        if (liveStream) {
            logger.debug("$debugLabel Live resume from cold pause; re-resolving playlist URLs.")
            env.cacheInvalidator.invalidate(url)
            forgetResolvedStreamUrls()
            primedStartPositionNanos = 0L
            lifecycle = PlayerState.Restarting
            dispatchInitialize(coalesce = true)
            return@safeExecute
        }
        val offset = if (lifecycle == PlayerState.Ended) 0L else clock.originNanos
        startStreams(ss, offset)
    }

    /** Pauses playback, capturing the current position for later resume. */
    fun pause() = safeExecute {
        pauseRequested = true
        if (!sessionManager.isPlaying) return@safeExecute
        if (!liveStream) {
            watchdog.stop()
            if (sessionManager.suspend(allowExternalProcess = true, retainBuffered = true)) {
                lifecycle = PlayerState.Paused
                return@safeExecute
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
        lifecycle = PlayerState.Paused
        stopSession()
    }

    /**
     * `true` when the session can be parked warm.
     *
     * Read from any thread.
     */
    fun canPark(): Boolean = isReady && sessionManager.canPark()

    /** Parks the player warm while its display sits out of render distance. */
    fun park() = safeExecute {
        watchdog.stop()
        if (!sessionManager.suspend()) watchdog.start()
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

    /** Seeks to an absolute position in nanos. */
    fun seekTo(nanos: Long, fire: Boolean) = safeExecute { doSeek(nanos, fire) }

    /** Seeks [s] seconds relative to the current position. */
    fun seekRelative(s: Double) = safeExecute {
        if (!isReady || !seekable) return@safeExecute
        val max = (getDuration() - 1).coerceAtLeast(0)
        if (max <= 0) return@safeExecute
        doSeek((getCurrentTime() + (s * 1e9).toLong()).coerceIn(0, max), true)
    }

    /**
     * Current playback position in nanos.
     *
     * Falls back to the frozen / seek offset when paused or not started.
     */
    fun getCurrentTime(): Long {
        sessionManager.parkedPositionNanos()?.let { return it }
        if (!isReady || !sessionManager.isPlaying) return clock.originNanos
        return clock.currentTime()
    }

    /**
     * Position to save / resume from.
     *
     * Identical to [getCurrentTime] in normal playback, but while a replay -> live bridge
     * is active it prefers the parked or bridge-edge position instead.
     */
    fun getResumePositionNanos(): Long =
        sessionManager.parkedPositionNanos() ?: sessionManager.activeBridgeEdgeNanos() ?: getCurrentTime()

    /** Stream duration in nanos, or 0 for live streams. */
    fun getDuration(): Long = if (liveStream) 0L else durationHintNanos

    /** Primes the first live start offset before initialization opens the decoder. */
    fun primeStartPosition(nanos: Long) {
        if (nanos >= 0L && !sessionManager.isPlaying) primedStartPositionNanos = nanos
    }

    /** `true` when this player resumed from a cached replay bootstrap (already positioned at the saved time). */
    fun isResumingFromReplay(): Boolean = startedFromReplay

    /**
     * Runs [callback] immediately if already initialized, otherwise queues it for when
     * initialization completes.
     *
     * The callback is called on the init thread.
     */
    fun whenInitialized(callback: () -> Unit) {
        if (initDrained || isReady) {
            callback(); return
        }
        initCallbacks.add(callback)
        if ((initDrained || isReady) && initCallbacks.remove(callback)) callback()
    }

    /**
     * Livestreams start playing immediately and may not support seeking.
     *
     * @return `true` if the stream is a live stream.
     */
    fun isLive(): Boolean = liveStream

    /** @return `true` if the stream supports seeking. */
    fun canSeek(): Boolean = isReady && seekable

    /** @return `true` once active playback is advancing (first frame arrived, not paused / parked). */
    fun isClockRunning(): Boolean =
        sessionManager.isPlaying && !sessionManager.isParked() && clock.isRunning

    /**
     * Connects or disconnects the popout window sink.
     *
     * Pass `null` to detach.
     */
    fun setPopoutSink(sink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?) {
        sessionManager.popoutFrameSink = sink
    }

    /**
     * Connects or disconnects the display menu preview sink.
     *
     * Pass `null` to detach.
     */
    fun setPreviewSink(sink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?) {
        sessionManager.previewFrameSink = sink
    }

    /** `true` once the first decoded frame is ready for GPU upload. */
    fun textureFilled(): Boolean = sessionManager.textureFilled()

    /** Discards any ready raw frame so the renderer will not show stale content after a timeline jump. */
    fun clearFrame() = sessionManager.clearFrame()

    /**
     * Uploads latest decoded frame to texture.
     *
     * Render thread only.
     */
    fun updateFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean =
        sessionManager.updateFrame(texture, w, h)

    /**
     * Uploads the latest planar I420 frame into the three plane textures.
     *
     * Render thread only.
     *
     * @return `true` if uploaded.
     */
    fun updateFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        sessionManager.updateFramePlanar(y, u, v, w, h)

    /** `true` while a parallel quality switch is warming up the new resolution. */
    fun hasIncomingVideo(): Boolean = sessionManager.hasIncoming()

    /**
     * Uploads the latest incoming (quality-switch) frame to [texture].
     *
     * Render thread only.
     *
     * @return `true` if uploaded.
     */
    fun updateIncomingFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean =
        sessionManager.updateIncomingFrame(texture, w, h)

    /**
     * Uploads the latest incoming planar I420 frame into the staged plane textures.
     *
     * @return `true` if uploaded.
     */
    fun updateIncomingFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        sessionManager.updateIncomingFramePlanar(y, u, v, w, h)

    /**
     * Promotes the warmed-up quality-switch channel to live.
     *
     * @return `false` if it was already aborted.
     */
    fun promoteIncomingVideo(): Boolean {
        val promoted = sessionManager.promoteIncoming()
        if (promoted) onControl { qualitySwitch = null }
        return promoted
    }

    private fun handleQualitySwitchAborted(appliedAnyway: Boolean) {
        host.cancelQualityHandoff()
        onControl {
            val rollback = qualitySwitch?.rollback
            qualitySwitch = null
            if (rollback == null || appliedAnyway) return@onControl
            media = media?.copy(streams = rollback.previousStreams, quality = rollback.previousQuality)
            host.videoContentAspect = rollback.previousStreams.currentVideo.contentAspect()
            if (lastRequestedQuality == rollback.target) lastRequestedQuality = rollback.previousQuality
        }
    }

    /**
     * Sets the user-controlled volume (0.0–2.0).
     *
     * Distance attenuation is applied on top.
     */
    fun setVolume(volume: Float) = this.volume.setUserVolume(volume)

    /** Seeds volume up-front (before audio starts) to avoid loud burst on reappear. */
    fun primeVolume(userVolume: Float, distance: Double, maxRadius: Double) {
        volume.setUserVolume(userVolume)
        volume.updateAttenuation(distance, maxRadius)
    }

    /** Sets the brightness multiplier applied to each frame before GPU upload (0.0–2.0). */
    fun setBrightness(brightness: Float) {
        this.brightness = brightness.toDouble().coerceIn(0.0, 2.0)
    }

    /** @return the list of available video quality levels (in pixels) for the current stream. */
    fun getAvailableQualities(): List<Int> {
        val cap = if (env.config.isPremium) 2160 else 1080
        return streams?.availableVideo.orEmpty().asSequence()
            .mapNotNull { it.height }
            .filter { it <= cap }
            .distinct().sorted().toList()
    }

    /** Switches to [quality]. */
    fun setQuality(quality: VideoQuality, userInitiated: Boolean = false) =
        safeExecute { changeQuality(quality, userInitiated) }

    /** @return selectable audio tracks (deduped by language). */
    fun getAvailableAudioTracks(): List<MediaStream> {
        val audio = streams?.availableAudio?.filter { !it.type.hasVideo } ?: return []
        return audio
            .groupBy { it.audioTrackLang ?: it.audioTrackName }
            .values
            .map { group -> group.maxByOrNull { it.bitrate ?: 0 } ?: group.first() }
    }

    /** URL of currently-playing audio track for UI highlighting (`null` before stream resolves). */
    fun getCurrentAudioTrack(): String? {
        val current = streams?.currentAudio ?: return null
        val key = current.audioTrackLang ?: current.audioTrackName
        return getAvailableAudioTracks()
            .firstOrNull { (it.audioTrackLang ?: it.audioTrackName) == key }?.url
            ?: current.url
    }

    /** Switches the active audio track to the one identified by [trackUrl]. */
    fun setAudioTrack(trackUrl: String) = safeExecute { changeAudioTrack(trackUrl) }

    /** `true` while a [setAudioTrack] switch is in flight; the actual audio can lag the UI selection by a few seconds. */
    fun isSwitchingAudioTrack(): Boolean = audioTrackSwitching

    /** Selectable subtitle tracks for the current video (empty when the source exposed none). */
    fun getAvailableSubtitleTracks(): List<SubtitleTrack> = subtitles.tracks

    /** Language of the currently selected subtitle track, or `null` when subtitles are off. */
    fun getCurrentSubtitleLang(): String? = subtitles.currentLang

    /** `true` while subtitles are enabled for this viewer, regardless of whether a cue is showing right now. */
    fun isSubtitlesEnabled(): Boolean = subtitles.enabled

    /**
     * Selects the subtitle track by [lang] and enables display, or disables subtitles when [lang] is
     * `null` or blank.
     *
     * Per-viewer only, never touches the decode pipeline.
     */
    fun setSubtitleTrack(lang: String?) = subtitles.select(lang)

    /** Current subtitle line for [getCurrentTime], or `null` when subtitles are off or no cue is active. */
    fun getCurrentSubtitleText(): String? = subtitles.textAt(getCurrentTime())

    /**
     * True while a [setQuality] change is still being applied: the new resolution decodes in a second
     * channel and only replaces the picture once its first frame lands, so the switch outlives the click.
     */
    fun isApplyingQuality(): Boolean {
        val switch = qualitySwitch ?: return false
        return switch.announced && System.nanoTime() - switch.startedNanos <= QUALITY_STATUS_MAX_NS
    }

    /**
     * Reopens the current stream without changing URL / quality;
     * used when render backend requirements change.
     */
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

    /** Captures resolved streams for fast reappear (`null` for live streams or before init). */
    fun capturePreparedMedia(): PreparedMedia? {
        if (liveStream) return null
        val ss = streams ?: return null
        return PreparedMedia(ss, liveStream, seekable, durationHintNanos)
    }

    /** Raw stream URL for scrub-preview extraction (`null` for live or unresolved). */
    fun capturedStreamRawUrl(): String? = capturePreparedMedia()?.streamSet?.currentVideo?.url

    /** Whether captured stream uses decode-forward seek path. */
    fun capturedStreamSeeksByDecoding(): Boolean =
        capturePreparedMedia()?.streamSet?.currentVideo?.seekByDecoding == true

    /** Updates distance-based volume attenuation (call every tick from game thread). */
    fun tick(distance: Double, maxRadius: Double) {
        if (!isReady) return
        volume.updateAttenuation(distance, maxRadius)
    }

    private fun onControl(action: () -> Unit) {
        if (Thread.currentThread() === controlThread) action() else safeExecute(action)
    }

    private fun dispatchInitialize(coalesce: Boolean = false) {
        if (terminated.get()) return
        if (!initGate.compareAndSet(InitGate.IDLE, InitGate.RUNNING)) {
            if (coalesce) initGate.compareAndSet(InitGate.RUNNING, InitGate.RUNNING_QUEUED)
            return
        }
        INIT_EXECUTOR.submit {
            try {
                if (!terminated.get()) initialize()
            } finally {
                if (initGate.getAndSet(InitGate.IDLE) == InitGate.RUNNING_QUEUED) dispatchInitialize()
            }
        }
    }

    private fun initialize() {
        onControl { lifecycle = PlayerState.Preparing }
        runCatching {
            val prepared = preparedBootstrap?.also { preparedBootstrap = null }
                ?: MediaPreparationService.prepare(url, lang, host.quality, env)

            if (terminated.get()) return

            val resolved = ResolvedMedia(
                streams = prepared.streamSet,
                live = prepared.isLive,
                seekable = prepared.isSeekable,
                durationNanos = prepared.durationNanos,
                quality = MediaStreamSelector.parseQuality(prepared.streamSet.currentVideo),
            )
            media = resolved
            host.videoContentAspect = prepared.streamSet.currentVideo.contentAspect()
            subtitles.reset(prepared.availableSubtitles)

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

            val primed = primedStartPositionNanos.takeIf { it >= 0L } ?: 0L
            val initialOffset = if (liveStream) 0L else replayBootstrap?.positionNanos ?: primed

            safeExecute { if (!terminated.get()) startStreams(prepared.streamSet, initialOffset) }
        }.onSuccess {
            drainInitCallbacks(run = true)
        }.onFailure { e ->
            logger.error("$debugLabel Initialization failed: ${e.message}.")
            onControl { lifecycle = PlayerState.Error }
            host.mediaError = e as? DreamMediaException
                ?: DreamMediaException.Unknown(
                    e.message ?: "initialization failed",
                    e
                )
            drainInitCallbacks(run = false)
        }
    }

    private fun startStreams(streamSet: ActiveStreams, offsetNanos: Long) {
        if (terminated.get()) return
        if (pauseRequested) {
            logger.debug("$debugLabel Start skipped: playback was paused while the media was being prepared.")
            lifecycle = PlayerState.Paused
            return
        }
        if (lifecycle == PlayerState.Ended) lifecycle = PlayerState.Paused
        val bootstrap = replayBootstrap?.also { replayBootstrap = null }
        val replayActive = sessionManager.isBridging()
        logger.debug(
            "$debugLabel [reappear] startStreams offset=${ms(offsetNanos)}ms " +
                    "replayActive=$replayActive bootstrap=${bootstrap != null} live=$liveStream",
        )
        if (replayActive && bootstrap != null && !liveStream) {
            if (attachLiveToReplay(streamSet, bootstrap.positionNanos)) return
            logger.debug("$debugLabel [reappear] attachLiveToReplay failed; falling back to cold start.")
        }
        host.cancelQualityHandoff()
        sessionStartNanos = System.nanoTime()
        sessionStartOffsetNanos = offsetNanos
        audioRestartAttempts = 0
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
            lifecycle = PlayerState.Playing
            watchdog.start()
            refreshWarmAudioTracks()
            qualitySwitch = qualitySwitch?.silenced()
        }
    }

    private fun startReplayBootstrapVideo(boot: ReplayBootstrap) {
        if (terminated.get()) return
        val resume = boot.positionNanos.coerceAtLeast(0L)
        if (sessionManager.startReplayVideoOnly(boot.snapshot, resume, boot.positionNanos)) {
            lifecycle = PlayerState.Playing
            logger.debug("$debugLabel Replay bootstrap shown instantly, resuming at ${ms(resume)}ms.")
        }
    }

    private fun attachLiveToReplay(streamSet: ActiveStreams, liveOffsetNanos: Long): Boolean {
        env.renderExecutor.execute { host.beginQualityHandoff() }
        sessionStartNanos = System.nanoTime()
        if (!sessionManager.attachLiveAfterReplay(streamSet, liveOffsetNanos, lastQuality, currentHwAccel())) {
            env.renderExecutor.execute { host.cancelQualityHandoff() }
            return false
        }
        lifecycle = PlayerState.Playing
        watchdog.start()
        logger.debug("$debugLabel Attached live after replay at ${ms(liveOffsetNanos)}ms.")
        return true
    }

    private fun beginQualitySwitch(
        streamSet: ActiveStreams,
        offsetNanos: Long,
        hwAccelOverride: HwAccelBackend? = null,
    ) {
        if (terminated.get()) return
        sessionManager.beginQualitySwitch(streamSet, offsetNanos, lastQuality, hwAccelOverride ?: currentHwAccel())
    }

    private fun currentHwAccel(): HwAccelBackend =
        if (env.config.useHwAccel && !hwAccelDisabled) HwAccelBackend.detectDefault() else HwAccelBackend.NONE

    private fun stopSession() {
        watchdog.stop()
        sessionManager.stop()
    }

    private fun handleStreamEnd(stderr: String, normalEos: Boolean): Unit = onControl {
        if (terminated.get()) return@onControl
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
            val offset = if (liveStream) 0L else sessionStartOffsetNanos
            if (ss != null) startStreams(ss, offset)
            return@onControl
        }

        val decision = retryPolicy.evaluate(stderr, normalEos, liveStream)
        if (decision != null) {
            scheduleRetry(decision.invalidateCache)
            return@onControl
        }

        if (normalEos && !liveStream) {
            if (host.shouldLoopOnEnd) {
                restartFromBeginning()
            } else {
                stopSession()
                lifecycle = PlayerState.Ended
                host.onPlaybackEnded(durationHintNanos)
            }
            return@onControl
        }

        if (stderr.isNotEmpty()) {
            logger.error("$debugLabel Unrecoverable: ${MediaUtil.truncate(stderr)}.")
        }
        lifecycle = PlayerState.Error
        host.mediaError = DreamMediaException.Decode("Unrecoverable stream failure", isFatal = true)
    }

    private fun restartFromBeginning() {
        if (restartPending) return
        restartPending = true
        safeExecute {
            try {
                val ss = streams
                if (ss != null && !terminated.get() && !host.isPaused) {
                    if (lifecycle == PlayerState.Ended) lifecycle = PlayerState.Paused
                    if (!sessionManager.beginSeek(ss, 0, lastQuality, currentHwAccel())) {
                        clock.reset(0)
                        startStreams(ss, 0)
                    }
                    events.onSeek()
                }
            } finally {
                restartPending = false
            }
        }
    }

    private fun scheduleRetry(invalidateCache: Boolean) {
        val delayMs = retryPolicy.nextDelay()
        logger.warn("$debugLabel ${if (invalidateCache) "Cache invalidated" else "Transient error"}. Retry ${retryPolicy.retries}/$MAX_FETCH_RETRIES in $delayMs ms.")
        if (invalidateCache) {
            env.cacheInvalidator.invalidate(url)
            forgetResolvedStreamUrls()
        }
        lifecycle = PlayerState.Restarting
        RETRY_SCHEDULER.schedule({ dispatchInitialize() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun handleAudioFailure(stderr: String): Unit = onControl {
        if (MediaProcess.indicatesNoAudioStream(stderr)) {
            val silentUrl = streams?.currentAudio?.url
            if (silentUrl == null || sessionManager.markSourceSilent(silentUrl)) {
                logger.info("$debugLabel This source carries no audio track; playing it silently.")
            }
            return@onControl
        }
        if (!liveStream && durationHintNanos > 0L && durationHintNanos - clock.currentTime() <= AUDIO_EOS_NEAR_END_GUARD_NS) {
            logger.debug("$debugLabel Audio pipe ended near VOD end (pos=${clock.currentTime()}, dur=$durationHintNanos); deferring to video EOS.")
            return@onControl
        }
        if (liveStream && sessionManager.audioSourceGone()) {
            handleSessionStall("live audio source stopped serving")
            return@onControl
        }
        if (liveStream && sessionManager.audioSpliced()) {
            restartAudioOrStall()
            return@onControl
        }
        val now = System.nanoTime()
        if (lastAudioFailureNanos != 0L && now - lastAudioFailureNanos > AUDIO_RESTART_BUDGET_RESET_NS) {
            audioRestartAttempts = 0
        }
        lastAudioFailureNanos = now
        val attempt = ++audioRestartAttempts
        if (liveStream && sessionManager.isPlaying && attempt <= MAX_AUDIO_RESTARTS) {
            logger.warn(
                "$debugLabel Live audio ended (${MediaUtil.truncate(stderr)}); " +
                        "restarting audio only ($attempt/$MAX_AUDIO_RESTARTS), video keeps playing."
            )
            RETRY_SCHEDULER.schedule({ safeExecute(::restartAudioOrStall) }, attempt * 1_000L, TimeUnit.MILLISECONDS)
            return@onControl
        }
        handleSessionStall("audio ended: ${MediaUtil.truncate(stderr)}.")
    }

    private fun restartAudioOrStall() {
        val ss = streams
        if (terminated.get() || ss == null) return
        if (!sessionManager.restartAudio(ss, 0L)) {
            handleSessionStall("audio restart not possible in current session state")
        }
    }

    private fun handleSessionStall(reason: String): Unit = onControl {
        if (terminated.get()) return@onControl
        val ss = streams ?: return@onControl
        val now = System.nanoTime()
        val repeated = lastStallNanos != 0L && now - lastStallNanos < REPEATED_STALL_WINDOW_NS
        lastStallNanos = now
        if (!repeated) stallReResolves = 0
        if (repeated || liveStream) {
            val kind = if (liveStream) "Live stall" else "Repeated stall"
            val attempt = ++stallReResolves
            if (attempt > MAX_STALL_RERESOLVES) {
                logger.error("$debugLabel $kind ($reason); giving up after $MAX_STALL_RERESOLVES re-resolves.")
                lifecycle = PlayerState.Error
                host.mediaError = DreamMediaException.Decode("Stream keeps stalling", isFatal = true)
                return@onControl
            }
            val delayMs = STALL_RERESOLVE_BACKOFF_MS[(attempt - 1).coerceAtMost(STALL_RERESOLVE_BACKOFF_MS.lastIndex)]
            logger.warn(
                "$debugLabel $kind ($reason); invalidating cached URLs and re-resolving " +
                        "($attempt/$MAX_STALL_RERESOLVES, in $delayMs ms)."
            )
            env.cacheInvalidator.invalidate(url)
            forgetResolvedStreamUrls()
            primedStartPositionNanos = if (liveStream) 0L else clock.currentTime()
            lifecycle = PlayerState.Restarting
            if (delayMs == 0L) dispatchInitialize()
            else RETRY_SCHEDULER.schedule({ dispatchInitialize() }, delayMs, TimeUnit.MILLISECONDS)
        } else {
            logger.warn("$debugLabel Stream stalled ($reason); restarting.")
            val pos = if (liveStream) 0L else clock.currentTime()
            if (sessionManager.beginSeek(ss, pos, lastQuality, currentHwAccel())) {
                watchdog.start()
            } else {
                startStreams(ss, pos)
            }
        }
    }

    private fun forgetResolvedStreamUrls() {
        val ss = streams ?: return
        MediaHostGuard.invalidate(ss.currentVideo.url)
        MediaHostGuard.invalidate(ss.currentAudio.url)
    }

    private fun doStop() {
        lifecycle = PlayerState.Stopped
        sessionManager.clearFrame()
        stats.stop()
        stopSession()
        sessionManager.cleanup()
        streams = null
    }

    private fun doSeek(nanos: Long, fire: Boolean) {
        if (!isReady || !seekable) return
        if (lifecycle == PlayerState.Ended) lifecycle = PlayerState.Paused
        if (isPausedWarm()) freezePausedWarmSession()
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
            if (userInitiated) qualitySwitch = QualitySwitch(System.nanoTime(), announced = true, rollback = null)
            primedStartPositionNanos = 0L
            lifecycle = PlayerState.Restarting
            dispatchInitialize(coalesce = true)
            return
        }
        if (isPausedWarm()) freezePausedWarmSession()
        val newSs = MediaStreamSelector.switchQuality(ss, target) ?: return
        val previousQuality = lastQuality
        media = media?.copy(streams = newSs, quality = MediaStreamSelector.parseQuality(newSs.currentVideo))
        host.videoContentAspect = newSs.currentVideo.contentAspect()
        env.renderExecutor.execute {
            if (sessionManager.isPlaying && !sessionManager.isParked()) {
                // Parallel quality switch: stage the new-resolution texture, but the live video keeps
                // decoding and rendering. The new resolution warms up in a second channel; fitTexture
                // promotes both (channel + texture) on its first frame, so the picture never freezes.
                // A genuine handoff failure rolls the metadata above back (see handleQualitySwitchAborted).
                host.beginQualityHandoff()
                safeExecute {
                    // Announced only here: a real handoff outlives the click by seconds, whereas the direct
                    // swap below finishes inside this very block, so flagging it would only ever flicker.
                    qualitySwitch = QualitySwitch(
                        System.nanoTime(), announced = userInitiated,
                        rollback = QualityRollback(ss, previousQuality, target),
                    )
                    beginQualitySwitch(newSs, getCurrentTime())
                }
            } else {
                // Nothing decoding (so, just paused): no frames would arrive to drive a handoff, so swap
                // directly — there's no async attempt in flight to roll back if this fails later.
                host.reloadTexture()
                safeExecute {
                    // A direct swap is done the moment it returns: there is no handoff to wait out.
                    qualitySwitch = null
                    clock.moveTo(getCurrentTime())
                }
            }
        }
    }

    private fun changeAudioTrack(trackUrl: String) {
        val ss = streams ?: return
        if (trackUrl == ss.currentAudio.url) return
        val newSs = MediaStreamSelector.switchAudioTrack(ss, trackUrl) ?: return
        streams = newSs
        audioTrackSwitching = true
        val seamless = sessionManager.beginAudioTrackSwitch(newSs)
        refreshWarmAudioTracks()
        if (seamless) return
        env.renderExecutor.execute {
            safeExecute {
                sessionManager.restartAudio(newSs, getCurrentTime())
                audioTrackSwitching = false
            }
        }
    }

    private fun refreshWarmAudioTracks() {
        val ss = streams
        if (ss == null || liveStream) {
            sessionManager.setWarmAudioTracks([])
            return
        }
        val playing = ss.currentAudio.url
        sessionManager.setWarmAudioTracks(
            getAvailableAudioTracks()
                .filter { it.url != playing }
                .map { WarmTrack(it.url, it.seekByDecoding) },
        )
    }

    private fun isPausedWarm(): Boolean =
        lifecycle == PlayerState.Paused && sessionManager.isParked()

    private fun freezePausedWarmSession() {
        val pos = sessionManager.parkedPositionNanos() ?: getCurrentTime()
        stopSession()
        clock.reset(pos)
        lifecycle = PlayerState.Paused
    }

    private fun drainInitCallbacks(run: Boolean) {
        if (run) initDrained = true
        // Remove one at a time by identity so a callback added mid-drain is never wiped without running
        while (true) {
            val cb = initCallbacks.firstOrNull() ?: break
            if (initCallbacks.remove(cb) && run) cb()
        }
    }

    private fun safeExecute(action: () -> Unit) {
        if (!terminated.get() && !controlExecutor.isShutdown)
            runCatching { controlExecutor.submit(action) }
    }
}
