package com.dreamdisplays.media.player

import com.dreamdisplays.api.media.player.PlaybackHost
import com.dreamdisplays.api.media.player.PlaybackEnvironment
import com.dreamdisplays.media.player.events.PlayerEvents
import com.dreamdisplays.media.player.managers.PlaybackSessionManager
import com.dreamdisplays.media.player.managers.StatsReporter
import com.dreamdisplays.media.player.managers.StreamWatchdog
import com.dreamdisplays.media.player.pipeline.AudioSink
import com.dreamdisplays.media.player.pipeline.PlaybackClock
import com.dreamdisplays.media.player.policy.RetryPolicy
import com.dreamdisplays.media.player.process.HwAccelBackend
import com.dreamdisplays.media.player.preparation.MediaPreparationService
import com.dreamdisplays.media.player.preparation.PreparedMedia
import com.dreamdisplays.media.player.stream.MediaStreamSelector
import com.dreamdisplays.media.player.stream.ActiveStreams
import com.dreamdisplays.media.player.util.MediaUtil
import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.api.media.DreamMediaException
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.VideoQuality
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.media.FramePixelFormat
import com.dreamdisplays.api.media.player.GpuTextureRef
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the lifecycle of a single media playback instance, including stream selection, `FFmpeg`
 * process management, playback state, and error handling. All public methods are thread-safe and
 * should be called from the game thread.
 *
 * @param youtubeUrl YouTube video URL
 * @param lang language code (e.g. "en", "ja")
 * @param host the display this player drives
 * @param env cross-cutting platform services (config, render thread, GPU upload, resolver)
 */
class MediaPlayer(
    private val youtubeUrl: String,
    private val lang: String,
    private val host: PlaybackHost,
    private val env: PlaybackEnvironment,
    private val replayBootstrap: ReplayBootstrap? = null,
) {
    /** One-shot native packet-cache bootstrap used when a local display reappears.
     *  [audioPcm] is the cached raw PCM for the same window, played during the bridge (null = silent bridge). */
    data class ReplayBootstrap(val snapshot: ByteArray, val positionNanos: Long, val audioPcm: ByteArray? = null) {
        /** Cached resolved streams for this URL, reused on reappear to skip the network `prepare()` so the
         *  live source warms in ~decoder-open time instead of ~seconds (null = none / re-resolve). */
        var prepared: PreparedMedia? = null
    }

    companion object {
        /** Logger. */
        private val logger = LoggerFactory.getLogger("DreamDisplays/MediaPlayer")

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

        /** Hwaccel failures show up within the first few seconds, past this window assume the stream is just unreliable. */
        private const val HWACCEL_FAIL_WINDOW_NS = 5_000_000_000L

        /**
         * A second stall within this window of the previous one means a plain restart isn't helping (most likely
         * a stale / throttled resolved URL rather than a transient hiccup), so escalate to a fresh re-resolve.
         */
        private const val REPEATED_STALL_WINDOW_NS = 90_000_000_000L

        /**
         * The audio and video sides of a VOD are two independent `FFmpeg` processes decoding the same
         * source, so their organic end-of-stream can land a moment apart. Within this window of the known
         * duration, the audio pipe reaching EOF is treated as the track finishing normally rather than a
         * crash, and is left to the video side's own EOS handling instead of triggering a stall recovery.
         */
        private const val AUDIO_EOS_NEAR_END_GUARD_NS = 3_000_000_000L

        /**
         * On reappearance, cached replay resumes this far before the saved position. Default 0 =
         * zero rewind: the saved frame is held (no backward jump) while the live source warms up, then live
         * continues forward from the saved position. A non-zero lead trades a short re-watch of cached
         * motion for a shorter hold; tunable via `-Ddreamdisplays.replayLeadMs` (must be <= the cache window).
         */
        private val REPLAY_LEAD_NS: Long =
            (System.getProperty("dreamdisplays.replayLeadMs")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L) * 1_000_000L

        /** Thread counter. */
        private val INIT_THREAD_COUNTER = AtomicInteger()

        /** Executor. */
        private val INIT_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
        ) { r -> daemon(r, "MediaPlayer-init-${INIT_THREAD_COUNTER.incrementAndGet()}") }

        /** Shared timer for retry back-off delays, so waiting never occupies an [INIT_EXECUTOR] thread. */
        private val RETRY_SCHEDULER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r -> daemon(r, "MediaPlayer-retry") }
    }

    private val debugLabel = "${host.uuid}/${Integer.toHexString(System.identityHashCode(this))}"
    private val terminated = AtomicBoolean(false)
    private val restartPending = AtomicBoolean(false)
    private val endedAtEnd = AtomicBoolean(false)

    private val clock = PlaybackClock()
    private val state = AtomicReference(PlaybackState.IDLE)
    private val replayBootstrapRef = AtomicReference(replayBootstrap)
    private val primedStartPositionNanos = AtomicLong(-1L)

    /** One-shot cached prepared streams from the bootstrap: consumed by the first [initialize] to skip the
     *  network resolve. Cleared after use so a retry (e.g. expired cached URLs) re-resolves fresh. */
    private val preparedBootstrapRef = AtomicReference(replayBootstrap?.prepared)

    /** True once replay-only video is rendering, so [startStreams] attaches live instead of cold-starting. */
    private val replayVideoActive = AtomicBoolean(false)

    /** True when this player was created from a replay bootstrap: it already resumes at the saved position,
     *  so the controller must NOT also fire restoreSavedTime (its corrective seek would cold-restart the bridge). */
    private val startedFromReplay = replayBootstrap != null
    private val retryPolicy = RetryPolicy(MAX_FETCH_RETRIES)

    private val events = PlayerEvents(
        onError = { e -> state.set(PlaybackState.ERROR); host.mediaError = e },
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

    /** Timestamp of the last stall recovery (watchdog or audio failure), 0 = none yet. Used to detect a second
     *  stall shortly after the first, which means the plain restart isn't fixing anything. */
    @Volatile
    private var lastStallNanos = 0L

    // Watchdog is created before sessionManager but its lambdas reference sessionManager lazily
    private val watchdog = StreamWatchdog(
        debugLabel = debugLabel,
        isActive = { sessionManager.isPlaying && !sessionManager.isParked() && !terminated.get() },
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
        onQualitySwitchAborted = { host.cancelQualityHandoff() },
        onAudioFailure = { stderr -> handleAudioFailure(stderr) },
        renderExecutor = env.renderExecutor,
        uploaderFactory = env.uploaderFactory,
        gpuYuvActive = env.config.gpuYuvActive,
    )

    private val controlExecutor = Executors.newSingleThreadExecutor { daemon(it, "MediaPlayer-ctrl") }
    private val initCallbacks = CopyOnWriteArrayList<() -> Unit>()

    /** Set once [initialize] has completed successfully; from then on [whenInitialized] runs callbacks
     *  immediately instead of queueing them (the queue is only drained at the end of [initialize]). */
    private val initDrained = AtomicBoolean(false)

    @Volatile
    private var streams: ActiveStreams? = null
    @Volatile
    private var liveStream = false
    @Volatile
    private var seekable = false
    @Volatile
    private var durationHintNanos = 0L
    @Volatile
    private var lastQuality = 0
    @Volatile
    private var brightness = 1.0
    @Volatile
    private var hwAccelDisabled = false
    @Volatile
    private var sessionStartNanos = 0L

    private val volume = VolumeController(env.config.defaultDisplayVolume) {
        sessionManager.setVolume(it)
    }

    init {
        // Show cached replay video immediately (network-free) so a reappearing display is instant,
        // in parallel with the live stream resolve happening on the init executor.
        replayBootstrap?.let { boot -> safeExecute { startReplayBootstrapVideo(boot) } }
        INIT_EXECUTOR.submit { initialize() }
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
        if (!isReady || !sessionManager.isPlaying) return clock.seekOffsetNanos
        return clock.currentTime()
    }

    /**
     * Position to save / resume from. Identical to [getCurrentTime] in normal playback, but while a
     * replay -> live bridge is in flight it returns the live edge instead of the replay playhead (which sits
     * up to [REPLAY_LEAD_NS] behind). This keeps the saved position from regressing — and from
     * compounding ~5 s per cycle — when a display is unloaded mid-bridge (rapid leave / return).
     */
    fun getResumePositionNanos(): Long =
        sessionManager.parkedPositionNanos() ?: sessionManager.activeBridgeEdgeNanos() ?: getCurrentTime()

    /** Stream duration in nanos, or 0 for live streams. */
    fun getDuration(): Long = if (liveStream) 0L else durationHintNanos

    /** Returns the current [PlaybackState]. */
    fun getState(): PlaybackState = state.get()

    /** Returns true once stream selection is complete and playback is active or paused. */
    fun isInitialized(): Boolean = isReady

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
        // initialize() may have drained between the check and the add; claim our own callback if so.
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
     * Uploads the latest decoded frame to [texture]. [w] / [h] are the expected frame dimensions
     * (the texture being uploaded into — the active texture, or the pending one during a quality
     * handoff). Returns true when a frame was actually uploaded. Render thread only.
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
    fun promoteIncomingVideo(): Boolean = sessionManager.promoteIncoming()

    /** Sets the user-controlled volume (0.0–2.0). Distance attenuation is applied on top. */
    fun setVolume(volume: Float) = this.volume.setUserVolume(volume)

    /**
     * Seeds the effective volume (user level + distance attenuation) up-front, before any audio starts.
     * The reappearance bridge's cached prelude begins at construction — before [start] or [tick] run — so
     * without this the first moment plays at the default (un-attenuated) level, audible as a loud burst.
     * Bypasses the [tick] ready-state guard.
     */
    fun primeVolume(userVolume: Float, distance: Double, maxRadius: Double) {
        volume.setUserVolume(userVolume)
        volume.updateAttenuation(distance, maxRadius)
    }

    /** Sets the brightness multiplier applied to each frame before GPU upload (0.0-2.0). */
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

    /** Switches to the closest available stream for [quality]. */
    fun setQuality(quality: VideoQuality) = safeExecute { changeQuality(quality) }

    /** Reopens the current stream without changing URL/quality; used when render backend requirements change. */
    fun restartVideoPipeline() = safeExecute {
        val ss = streams ?: return@safeExecute
        if (isPausedWarm()) freezePausedWarmSession()
        val pos = if (liveStream) 0L else getCurrentTime()
        env.renderExecutor.execute {
            host.reloadTexture()
            safeExecute {
                if (sessionManager.isPlaying && !sessionManager.isParked()) startStreams(ss, pos)
                else clock.seekOffsetNanos = pos
            }
        }
    }

    /** Captures the active native LAV packet-ring snapshot (whole window), if the live channel has one. */
    fun captureReplaySnapshot(): ByteArray? = sessionManager.captureVideoCacheSnapshot()

    /** Captures the recent PCM window (matching the replay video lead) for the reappearance audio bridge. */
    fun captureReplayAudio(): ByteArray? = sessionManager.captureAudioPcm(REPLAY_LEAD_NS)

    /** Captures the resolved streams so a reappearing player can skip the network resolve. Null while not
     *  yet initialized or for live streams (which are never cache-bridged). */
    fun capturePreparedMedia(): PreparedMedia? {
        if (liveStream) return null
        val ss = streams ?: return null
        return PreparedMedia(ss, liveStream, seekable, durationHintNanos)
    }

    /**
     * Updates distance-based volume attenuation. Call every tick from the game thread.
     *
     * @param distance current distance from the player to the screen
     * @param maxRadius radius beyond which the screen is silent
     */
    fun tick(distance: Double, maxRadius: Double) {
        if (!isReady) return
        volume.updateAttenuation(distance, maxRadius)
    }

    /**
     * Runs on [INIT_EXECUTOR]. Delegates to [MediaPreparationService], updates metadata fields,
     * sets state to [PlaybackState.PLAYING], and fires [whenInitialized] callbacks.
     * On failure marks the screen as errored; on success starts playback.
     */
    private fun initialize() {
        state.set(PlaybackState.INITIALIZING)
        var success = false
        try {
            val cached = preparedBootstrapRef.getAndSet(null)
            if (cached != null) logger.debug("$debugLabel [reappear] reusing cached prepared streams; skipping prepare().")
            val prepared = cached ?: MediaPreparationService.prepare(youtubeUrl, lang, host.quality, env)
            if (terminated.get()) return

            liveStream = prepared.isLive
            seekable = prepared.isSeekable
            durationHintNanos = prepared.durationNanos
            streams = prepared.streamSet
            lastQuality = MediaStreamSelector.parseQuality(prepared.streamSet.currentVideo)
            host.videoContentAspect = prepared.streamSet.currentVideo.contentAspect()
            retryPolicy.reset()

            if (DEBUG) {
                logger.debug("$debugLabel video=${prepared.streamSet.currentVideo} audio=${prepared.streamSet.currentAudio}")
                logger.debug("$debugLabel live=$liveStream seekable=$seekable dur=$durationHintNanos")
                stats.start()
            }
            success = true
            val ss = prepared.streamSet
            val primed = primedStartPositionNanos.get().takeIf { it >= 0L } ?: 0L
            val initialOffset = replayBootstrapRef.get()?.positionNanos ?: primed
            safeExecute { if (!terminated.get()) startStreams(ss, initialOffset) }
        } catch (e: DreamMediaException) {
            logger.error("$debugLabel Initialization failed: ${e.message}")
            state.set(PlaybackState.ERROR)
            host.mediaError = e
        } catch (e: Exception) {
            logger.error("$debugLabel Initialization failed: ${e.message}")
            state.set(PlaybackState.ERROR)
            host.mediaError = DreamMediaException.Unknown(e.message ?: "Initialization failed", e)
        } finally {
            drainInitCallbacks(run = success)
        }
    }

    /**
     * Starts [sessionManager] for the given [streamSet] at [offsetNanos], then starts the watchdog.
     * Must be called from the control executor thread.
     */
    private fun startStreams(streamSet: ActiveStreams, offsetNanos: Long) {
        if (terminated.get()) return
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
        sessionManager.start(streamSet, offsetNanos, lastQuality, currentHwAccel())
        if (sessionManager.isPlaying) {
            state.set(PlaybackState.PLAYING)
            watchdog.start()
        }
    }

    /**
     * Immediately starts cached replay video (no network, no audio) so a returning display shows frames
     * before [initialize] finishes resolving the live stream. Resumes [REPLAY_LEAD_NS] before the saved
     * position so the buffered window can bridge the live re-resolve. Runs on the control thread.
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
     * The live stream is resolved while replay video is still playing: warm the live audio + video and
     * hand the picture off by PTS at the saved live edge. Returns false when live cannot be attached,
     * letting [startStreams] fall back to a cold start.
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
     * Seamless quality switch: warms up the new-quality video as a parallel channel while audio, the
     * clock, and the currently rendered video all keep running. The render thread swaps to the new
     * channel on its first frame, so the picture never freezes. Must be called from the control thread.
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
     * Stops the watchdog and the current session.
     */
    private fun stopSession() {
        watchdog.stop()
        sessionManager.stop()
    }

    /**
     * Called by [PlaybackSessionManager] via [onStreamEnd] when a stream finishes.
     * Delegates the retry decision to [RetryPolicy]; stops Local on normal EOS for VOD;
     * marks the screen as errored on unrecoverable failure.
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
            if (host.effectiveMode == PlaybackMode.LOCAL) completeVodPlayback()
            else restartFromBeginning()
            return
        }

        if (stderr.isNotEmpty()) {
            logger.error("$debugLabel Unrecoverable: ${MediaUtil.truncate(stderr)}.")
        }
        state.set(PlaybackState.ERROR)
        host.mediaError = DreamMediaException.Decode("Unrecoverable stream failure", isFatal = true)
    }

    /** Loops non-local VOD modes after a normal end, seeking back to 0 in place when possible so the
     *  wrap-around holds the last frame instead of blanking through a cold restart. */
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

    /** Stops a non-looping VOD at its end, keeping the last uploaded frame on screen. */
    private fun completeVodPlayback() {
        safeExecute {
            if (terminated.get()) return@safeExecute
            val endPosition = durationHintNanos.takeIf { it > 0L } ?: clock.currentTime().coerceAtLeast(0L)
            endedAtEnd.set(true)
            clock.seekOffsetNanos = endPosition
            state.set(PlaybackState.PAUSED)
            env.renderExecutor.execute { host.onPlaybackEnded(endPosition) }
            stopSession()
        }
    }

    /**
     * Schedules a re-initialization after an exponential back-off delay.
     * Purges the `yt-dlp` URL cache first when [invalidateCache] is true.
     */
    private fun scheduleRetry(invalidateCache: Boolean) {
        val delayMs = retryPolicy.nextDelay()
        logger.warn("$debugLabel ${if (invalidateCache) "Cache invalidated" else "Transient error"}. Retry ${retryPolicy.retries}/$MAX_FETCH_RETRIES in ${delayMs} ms.")
        if (invalidateCache) env.cacheInvalidator.invalidate(youtubeUrl)
        state.set(PlaybackState.RESTARTING)
        RETRY_SCHEDULER.schedule({
            if (!terminated.get()) INIT_EXECUTOR.submit { if (!terminated.get()) initialize() }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Filters an audio-pipe-ended notification before treating it as a stall: within
     * [AUDIO_EOS_NEAR_END_GUARD_NS] of a known VOD duration, the audio side finishing first is expected
     * (see [AUDIO_EOS_NEAR_END_GUARD_NS]), so it's left to the video side's own normal-EOS handling.
     */
    private fun handleAudioFailure(stderr: String) {
        if (!liveStream && durationHintNanos > 0L && durationHintNanos - clock.currentTime() <= AUDIO_EOS_NEAR_END_GUARD_NS) {
            logger.debug("$debugLabel Audio pipe ended near VOD end (pos=${clock.currentTime()}, dur=$durationHintNanos); deferring to video EOS.")
            return
        }
        handleSessionStall("audio ended: ${MediaUtil.truncate(stderr)}.")
    }

    /**
     * Recovers from a session that stopped delivering (video watchdog stall, or the audio process dying on
     * its own while video kept going). A single stall just restarts the same resolved streams — usually a
     * transient hiccup. A second stall shortly after means the restart didn't help, most likely because the
     * resolved URL itself is bad (e.g. throttled without cookies), so this escalates to a fresh re-resolve,
     * priming the current position first so it doesn't jump back to the start.
     */
    private fun handleSessionStall(reason: String) {
        if (terminated.get()) return
        val ss = streams ?: return
        val now = System.nanoTime()
        val repeated = lastStallNanos != 0L && now - lastStallNanos < REPEATED_STALL_WINDOW_NS
        lastStallNanos = now
        if (repeated) {
            logger.warn("$debugLabel Repeated stall ($reason); invalidating cached URLs and re-resolving.")
            env.cacheInvalidator.invalidate(youtubeUrl)
            primedStartPositionNanos.set(if (liveStream) 0L else clock.currentTime())
            state.set(PlaybackState.RESTARTING)
            safeExecute { if (!terminated.get()) initialize() }
        } else {
            logger.warn("$debugLabel Stream stalled ($reason); restarting.")
            safeExecute {
                val pos = if (liveStream) 0L else clock.currentTime()
                // Restart in place when possible: the picture holds its last frame while the new
                // session connects, instead of blanking through a blocking teardown.
                if (!sessionManager.beginSeek(ss, pos, lastQuality, currentHwAccel())) startStreams(ss, pos)
            }
        }
    }

    /** Starts `FFmpeg` from the current seek offset. No-op if already playing or not ready. */
    private fun doPlay() {
        if (!isReady || terminated.get()) return
        if (isPausedWarm()) {
            sessionManager.resume()
            state.set(PlaybackState.PLAYING)
            watchdog.start()
            return
        }
        if (sessionManager.isPlaying) return
        val ss = streams ?: return
        val offset = if (endedAtEnd.getAndSet(false)) 0L else clock.seekOffsetNanos
        startStreams(ss, offset)
    }

    /**
     * Pauses at the current position. VOD sessions on every pipeline stay warm (decoder / process and
     * audio line kept open, position frozen) so resume is immediate; only live streams and sessions in
     * a transitional state (bridge / quality switch) fall back to the cold pause path.
     */
    private fun doPause() {
        if (!sessionManager.isPlaying) return
        if (!liveStream) {
            watchdog.stop()
            if (sessionManager.suspend(allowExternalProcess = true)) {
                state.set(PlaybackState.PAUSED)
                return
            }
        }
        val fp = sessionManager.audioFramePosition
        clock.seekOffsetNanos = if (fp >= 0) clock.audioClockNanos(fp, AudioSink.SAMPLE_RATE) else clock.currentTime()
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
     * Moves the seek offset to [nanos]. While playing this is an in-place seek: the picture freezes on
     * its last frame and jumps once the target's first frame lands, with the old session dismantled in
     * the background instead of a blocking teardown-then-cold-start. Falls back to a full restart when
     * the session cannot be seeked in place.
     */
    private fun doSeek(nanos: Long, fire: Boolean) {
        if (!isReady || !seekable) return
        endedAtEnd.set(false)
        if (isPausedWarm()) freezePausedWarmSession()
        clock.seekOffsetNanos = nanos
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
    private fun changeQuality(desired: VideoQuality) {
        val ss = streams ?: return
        val target = desired.targetHeight ?: return
        if (target == lastQuality) {
            if (DEBUG) logger.debug("$debugLabel Quality switch no-op target=$target last=$lastQuality.")
            return
        }
        if (isPausedWarm()) freezePausedWarmSession()
        val newSs = MediaStreamSelector.switchQuality(ss, target, lang) ?: return
        val pos = if (liveStream) 0L else getCurrentTime()
        streams = newSs
        lastQuality = MediaStreamSelector.parseQuality(newSs.currentVideo)
        host.videoContentAspect = newSs.currentVideo.contentAspect()
        env.renderExecutor.execute {
            if (sessionManager.isPlaying && !sessionManager.isParked()) {
                // Parallel quality switch: stage the new-resolution texture, but the live video keeps
                // decoding and rendering. The new resolution warms up in a second channel; fitTexture
                // promotes both (channel + texture) on its first frame, so the picture never freezes.
                host.beginQualityHandoff()
                safeExecute { beginQualitySwitch(newSs, pos) }
            } else {
                // Nothing decoding (so, just paused): no frames would arrive to drive a handoff, so swap directly
                host.reloadTexture()
                safeExecute { clock.seekOffsetNanos = pos }
            }
        }
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
        // Remove one at a time by identity so a callback added mid-drain is never wiped without running.
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
