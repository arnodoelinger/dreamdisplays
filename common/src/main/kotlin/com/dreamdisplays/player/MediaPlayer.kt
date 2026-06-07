package com.dreamdisplays.player

import com.dreamdisplays.Initializer
import com.dreamdisplays.display.DisplayScreen
import com.dreamdisplays.managers.ClientStateManager
import com.dreamdisplays.player.events.PlayerEvents
import com.dreamdisplays.player.managers.PlaybackSessionManager
import com.dreamdisplays.player.managers.StatsReporter
import com.dreamdisplays.player.managers.StreamWatchdog
import com.dreamdisplays.player.pipeline.AudioSink
import com.dreamdisplays.player.pipeline.PlaybackClock
import com.dreamdisplays.player.policy.RetryPolicy
import com.dreamdisplays.player.process.HwAccelBackend
import com.dreamdisplays.player.preparation.MediaPreparationService
import com.dreamdisplays.player.stream.MediaStreamSelector
import com.dreamdisplays.player.stream.StreamSet
import com.dreamdisplays.player.util.MediaUtil
import com.dreamdisplays.player.util.daemon
import com.dreamdisplays.ytdlp.YtDlp
import com.mojang.blaze3d.textures.GpuTexture
import net.minecraft.core.BlockPos
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Manages the lifecycle of a single media playback instance, including stream selection, `FFmpeg`
 * process management, playback state, and error handling. All public methods are thread-safe and
 * should be called from the game thread.
 *
 * @param youtubeUrl YouTube video URL
 * @param lang language code (e.g. "en", "ja")
 * @param displayScreen the screen that will be displayed
 */
class MediaPlayer(
    private val youtubeUrl: String,
    private val lang: String,
    private val displayScreen: DisplayScreen,
) {
    companion object {
        private val logger = LoggerFactory.getLogger("DreamDisplays/MediaPlayer")
        val DEBUG: Boolean = System.getProperty("dreamdisplays.debug")?.toBoolean() == true
                || System.getenv("DREAMDISPLAYS_DEBUG").let { it == "1" || it.equals("true", ignoreCase = true) }
        var captureSamples: Boolean = true
        internal val samplesIn = AtomicLong()
        internal val framesToGpu = AtomicLong()
        internal val framesDropped = AtomicLong()
        private const val STOP_WAIT_TIMEOUT_SECONDS = 3L
        private const val MAX_FETCH_RETRIES = 3

        /** Hwaccel failures show up within the first few seconds, past this window assume the stream is just unreliable. */
        private const val HWACCEL_FAIL_WINDOW_NS = 5_000_000_000L
        private val INIT_THREAD_COUNTER = AtomicInteger()
        private val INIT_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
        ) { r -> daemon(r, "MediaPlayer-init-${INIT_THREAD_COUNTER.incrementAndGet()}") }
    }

    private val debugLabel = "${displayScreen.uuid}/${Integer.toHexString(System.identityHashCode(this))}"
    private val terminated = AtomicBoolean(false)
    private val restartPending = AtomicBoolean(false)

    private val clock = PlaybackClock()
    private val state = AtomicReference(PlaybackState.IDLE)
    private val retryPolicy = RetryPolicy(MAX_FETCH_RETRIES)

    private val events = PlayerEvents(
        onError = { state.set(PlaybackState.ERROR); displayScreen.errored = true },
        onSeek = { displayScreen.afterSeek() },
    )

    private val stats = StatsReporter(
        debugLabel = debugLabel,
        pollCounters = { StatsReporter.Snapshot(samplesIn.getAndSet(0), framesToGpu.getAndSet(0), framesDropped.getAndSet(0)) },
        getPositionMs = { getCurrentTime() / 1_000_000L },
        isLive = { liveStream },
    )

    // Watchdog is created before sessionManager but its lambdas reference sessionManager lazily
    private val watchdog = StreamWatchdog(
        debugLabel = debugLabel,
        isActive = { sessionManager.isPlaying && !terminated.get() },
        getLastFrameNanos = { sessionManager.lastFrameNanos.get() },
        onStall = {
            val ss = streams
            if (ss != null) {
                safeExecute { startStreams(ss, if (liveStream) 0L else clock.currentTime()) }
            }
        },
    )
    private val sessionManager = PlaybackSessionManager(
        debugLabel = debugLabel,
        clock = clock,
        events = events,
        terminated = terminated,
        getTextureSize = { displayScreen.textureWidth to displayScreen.textureHeight },
        getBrightness = { brightness },
        onStreamEnd = ::handleStreamEnd,
    )

    private val controlExecutor = Executors.newSingleThreadExecutor { daemon(it, "MediaPlayer-ctrl") }
    private val initCallbacks = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var streams: StreamSet? = null
    @Volatile private var liveStream = false
    @Volatile private var seekable = false
    @Volatile private var durationHintNanos = 0L
    @Volatile private var lastQuality = 0
    @Volatile private var brightness = 1.0
    @Volatile private var userVolume = ClientStateManager.config.defaultDisplayVolume
    @Volatile private var lastAttenuation = 1.0
    @Volatile private var hwAccelDisabled = false
    @Volatile private var sessionStartNanos = 0L

    init { INIT_EXECUTOR.submit { initialize() } }

    /** Resumes playback from the current seek position. No-op if already playing. */
    fun play() = safeExecute(::doPlay)

    /** Pauses playback, capturing the current position for later resume. */
    fun pause() = safeExecute(::doPause)

    /** Stops playback permanently; the instance must not be used after this call. */
    fun stop() {
        if (terminated.getAndSet(true)) return
        state.set(PlaybackState.STOPPED)
        val future = runCatching { controlExecutor.submit(::doStop) }.getOrNull()
        runCatching { future?.get(STOP_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.onFailure { doStop() }
        controlExecutor.shutdownNow()
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

    /** Current playback position in nanos. Falls back to seek offset when paused or not started. */
    fun getCurrentTime(): Long {
        if (!isReady || !sessionManager.isPlaying) return clock.seekOffsetNanos
        return clock.currentTime()
    }

    /** Stream duration in nanos, or 0 for live streams. */
    fun getDuration(): Long = if (liveStream) 0L else durationHintNanos

    /** Returns the current [PlaybackState]. */
    fun getState(): PlaybackState = state.get()

    /** Returns true once stream selection is complete and playback is active or paused. */
    fun isInitialized(): Boolean = isReady

    /**
     * Runs [callback] immediately if already initialized, otherwise queues it for when
     * initialization completes. The callback is called on the init thread.
     */
    fun whenInitialized(callback: () -> Unit) {
        if (isReady) { callback(); return }
        initCallbacks.add(callback)
        if (isReady && initCallbacks.remove(callback)) callback()
    }

    /**
     * Returns true if the stream is a live stream. Livestreams start playing immediately
     * and may not support seeking. Based on `yt-dlp` metadata; not always perfectly reliable.
     */
    fun isLive(): Boolean = liveStream

    /** Returns true if the stream supports seeking. */
    fun canSeek(): Boolean = isReady && seekable

    /** Returns true once the wall clock is running (first frame has arrived). */
    fun isClockRunning(): Boolean = clock.isRunning

    /** Connects or disconnects the popout window sink. Pass null to detach. */
    fun setPopoutSink(sink: ((ByteBuffer, Int, Int) -> Unit)?) {
        sessionManager.popoutFrameSink = sink
    }

    /** True once the first decoded frame is ready for GPU upload. */
    fun textureFilled(): Boolean = sessionManager.textureFilled()

    /** Uploads the latest decoded frame to [texture]. Must be called from the render thread. */
    fun updateFrame(texture: GpuTexture) =
        sessionManager.updateFrame(texture, displayScreen.textureWidth, displayScreen.textureHeight)

    /** Sets the user-controlled volume (0.0–2.0). Distance attenuation is applied on top. */
    fun setVolume(volume: Float) {
        userVolume = volume.toDouble().coerceIn(0.0, 2.0)
        sessionManager.setVolume(userVolume * lastAttenuation)
    }

    /** Sets the brightness multiplier applied to each frame before GPU upload (0.0-2.0). */
    fun setBrightness(brightness: Float) {
        this.brightness = brightness.toDouble().coerceIn(0.0, 2.0)
    }

    /** Returns the list of available video quality levels (in pixels) for the current stream. */
    fun getAvailableQualities(): List<Int> {
        val cap = if (ClientStateManager.isPremium) 2160 else 1080
        return streams?.availableVideo.orEmpty().asSequence()
            .mapNotNull { it.resolution }
            .map { MediaStreamSelector.parseQualityValue(it, Int.MAX_VALUE) }
            .filter { it != Int.MAX_VALUE && it <= cap }
            .distinct().sorted().toList()
    }

    /** Switches to the closest available stream for [quality] (e.g. "720p"). */
    fun setQuality(quality: String) = safeExecute { changeQuality(quality) }

    /**
     * Updates distance-based volume attenuation. Call every tick from the game thread.
     *
     * @param playerPos player block position
     * @param maxRadius radius beyond which the screen is silent
     */
    fun tick(playerPos: BlockPos, maxRadius: Double) {
        if (!isReady) return
        val attenuation = (1.0 - minOf(1.0, displayScreen.getDistanceToScreen(playerPos) / maxRadius)).let { it * it }
        if (abs(attenuation - lastAttenuation) > 1e-5) {
            lastAttenuation = attenuation
            sessionManager.setVolume(userVolume * attenuation)
        }
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
            val prepared = MediaPreparationService.prepare(youtubeUrl, lang, displayScreen.quality)
            if (terminated.get()) return

            liveStream = prepared.isLive
            seekable = prepared.isSeekable
            durationHintNanos = prepared.durationNanos
            streams = prepared.streamSet
            lastQuality = MediaStreamSelector.parseQuality(prepared.streamSet.currentVideo)
            displayScreen.videoContentAspect = prepared.streamSet.currentVideo.contentAspect()
            retryPolicy.reset()

            if (DEBUG) {
                logger.info("$debugLabel video=${prepared.streamSet.currentVideo} audio=${prepared.streamSet.currentAudio}")
                logger.info("$debugLabel live=$liveStream seekable=$seekable dur=$durationHintNanos")
                stats.start()
            }
            success = true
            val ss = prepared.streamSet
            safeExecute { if (!terminated.get()) startStreams(ss, 0) }
        } catch (e: Exception) {
            logger.error("$debugLabel Initialization failed: ${e.message}")
            state.set(PlaybackState.ERROR)
            displayScreen.errored = true
        } finally {
            drainInitCallbacks(run = success)
        }
    }

    /**
     * Starts [sessionManager] for the given [streamSet] at [offsetNanos], then starts the watchdog.
     * Must be called from the control executor thread.
     */
    private fun startStreams(streamSet: StreamSet, offsetNanos: Long) {
        if (terminated.get()) return
        val hwAccel =
            if (ClientStateManager.config.useHwAccel && !hwAccelDisabled) HwAccelBackend.detectDefault()
            else HwAccelBackend.NONE
        sessionStartNanos = System.nanoTime()
        sessionManager.start(streamSet, offsetNanos, lastQuality, hwAccel)
        if (sessionManager.isPlaying) {
            state.set(PlaybackState.PLAYING)
            watchdog.start()
        }
    }

    /**
     * Stops the watchdog and the current session.
     */
    private fun stopSession() {
        watchdog.stop()
        sessionManager.stop()
    }

    /**
     * Called by [PlaybackSessionManager] via [onStreamEnd] when a stream finishes.
     * Delegates the retry decision to [RetryPolicy]; loops the video on normal EOS for VOD;
     * marks the screen as errored on unrecoverable failure.
     */
    private fun handleStreamEnd(stderr: String, normalEos: Boolean) {
        if (terminated.get()) return
        if (!hwAccelDisabled && !normalEos && !clock.isRunning
            && System.nanoTime() - sessionStartNanos < HWACCEL_FAIL_WINDOW_NS
            && HwAccelBackend.looksLikeHwAccelFailure(stderr)
        ) {
            hwAccelDisabled = true
            logger.warn("$debugLabel Hardware decode failed for this stream. Falling back to software. Stderr: ${MediaUtil.truncate(stderr)}.")
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
            if (restartPending.compareAndSet(false, true)) {
                safeExecute {
                    try {
                        val ss = streams
                        if (ss != null && !terminated.get() && !displayScreen.isPaused) {
                            clock.reset(0)
                            startStreams(ss, 0)
                            events.onSeek()
                        }
                    } finally { restartPending.set(false) }
                }
            }
            return
        }

        if (stderr.isNotEmpty()) {
            logger.error("$debugLabel Unrecoverable: ${MediaUtil.truncate(stderr)}.")
        }
        state.set(PlaybackState.ERROR)
        displayScreen.errored = true
    }

    /**
     * Schedules a re-initialization after an exponential back-off delay.
     * Purges the `yt-dlp` URL cache first when [invalidateCache] is true.
     */
    private fun scheduleRetry(invalidateCache: Boolean) {
        val delayMs = retryPolicy.nextDelay()
        logger.warn("$debugLabel ${if (invalidateCache) "Cache invalidated" else "Transient error"}. Retry ${retryPolicy.retries}/$MAX_FETCH_RETRIES in ${delayMs} ms.")
        if (invalidateCache) YtDlp.invalidateCache(youtubeUrl)
        state.set(PlaybackState.RESTARTING)
        INIT_EXECUTOR.submit {
            runCatching { Thread.sleep(delayMs) }.onFailure { Thread.currentThread().interrupt(); return@submit }
            if (!terminated.get()) initialize()
        }
    }

    /** Starts `FFmpeg` from the current seek offset. No-op if already playing or not ready. */
    private fun doPlay() {
        if (!isReady || terminated.get() || sessionManager.isPlaying) return
        val ss = streams ?: return
        startStreams(ss, clock.seekOffsetNanos)
    }

    /**
     * Captures position from the audio clock when available, then stops the session.
     */
    private fun doPause() {
        if (!sessionManager.isPlaying) return
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
     * Moves the seek offset to [nanos] and, if playing, restarts `FFmpeg` from that position.
     */
    private fun doSeek(nanos: Long, fire: Boolean) {
        if (!isReady || !seekable) return
        clock.seekOffsetNanos = nanos
        val ss = streams ?: return
        if (sessionManager.isPlaying) startStreams(ss, nanos)
        if (fire) events.onSeek()
    }

    /**
     * Picks the closest available stream to [desired] quality. Updates [streams] via copy
     * and restarts `FFmpeg` when playing, or repositions seek offset when paused.
     */
    private fun changeQuality(desired: String) {
        val ss = streams ?: return
        val target = MediaStreamSelector.parseQualityValue(desired, -1)
        if (target < 0 || target == lastQuality) return
        val best = MediaStreamSelector.pickVideo(ss.availableVideo, target)
            ?.takeIf { it.url != ss.currentVideo.url } ?: return
        val chosenAudio = MediaStreamSelector.pickAudio(ss.availableAudio, lang, best) ?: ss.currentAudio
        val pos = if (liveStream) 0L else getCurrentTime()
        val newSs = ss.copy(currentVideo = best, currentAudio = chosenAudio)
        streams = newSs
        lastQuality = MediaStreamSelector.parseQuality(best)
        displayScreen.videoContentAspect = best.contentAspect()
        if (sessionManager.isPlaying) startStreams(newSs, pos) else clock.seekOffsetNanos = pos
    }

    private fun com.dreamdisplays.ytdlp.YtStream.contentAspect(): Double {
        val w = width ?: return 0.0
        val h = height ?: return 0.0
        return if (w > 0 && h > 0) w / h.toDouble() else 0.0
    }

    /**
     * Atomically drains [initCallbacks] and invokes each callback when [run] is true.
     */
    private fun drainInitCallbacks(run: Boolean) {
        initCallbacks.toList().also { initCallbacks.clear() }.takeIf { run }?.forEach { it() }
    }

    /** Submits [action] to the control executor if the player is not terminated. */
    private fun safeExecute(action: () -> Unit) {
        if (!terminated.get() && !controlExecutor.isShutdown)
            runCatching { controlExecutor.submit(action) }
    }

    /** True when the player is in a state where playback operations are valid. */
    private val isReady: Boolean
        get() = state.get().let { it == PlaybackState.PLAYING || it == PlaybackState.PAUSED || it == PlaybackState.RESTARTING }
}
