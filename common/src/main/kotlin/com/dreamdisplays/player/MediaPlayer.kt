package com.dreamdisplays.player

import com.dreamdisplays.Initializer
import com.dreamdisplays.display.DisplayScreen
import com.dreamdisplays.ffmpeg.FFmpegBinary
import com.dreamdisplays.player.pipeline.AudioSink
import com.dreamdisplays.player.pipeline.PlaybackClock
import com.dreamdisplays.player.pipeline.VideoFramePipe
import com.dreamdisplays.player.process.MediaProcess
import com.dreamdisplays.player.stream.MediaStreamSelector
import com.dreamdisplays.player.util.MediaUtil
import com.dreamdisplays.player.util.daemon
import com.dreamdisplays.player.util.joinSafely
import com.dreamdisplays.utils.GeneralUtil
import com.dreamdisplays.ytdlp.YtDlp
import com.dreamdisplays.ytdlp.YtStream
import com.mojang.blaze3d.textures.GpuTexture
import me.inotsleep.utils.logging.LoggingManager
import net.minecraft.core.BlockPos
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Media player for a single YouTube video.
 *
 * Orchestrates stream selection, two FFmpeg processes (video + audio), a [VideoFramePipe]
 * for frame buffering, an [AudioSink] for PCM playback, a [PlaybackClock] for A/V sync,
 * and a watchdog that restarts stalled streams automatically.
 */
class MediaPlayer(
    private val youtubeUrl: String,
    private val lang: String,
    private val displayScreen: DisplayScreen,
) {

    companion object {

        val DEBUG: Boolean = System.getProperty("dreamdisplays.debug")?.toBoolean() == true
                || System.getenv("DREAMDISPLAYS_DEBUG").let { it == "1" || it.equals("true", ignoreCase = true) }

        var captureSamples: Boolean = true

        internal val samplesIn = AtomicLong()
        internal val framesToGpu = AtomicLong()
        internal val framesDropped = AtomicLong()

        private const val STOP_WAIT_TIMEOUT_SECONDS = 3L
        private const val STATS_INTERVAL_MS = 2000L
        private const val MAX_FETCH_RETRIES = 3
        private const val WATCHDOG_TIMEOUT_NS = 30_000_000_000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 5000L

        private val RETRY_BACKOFF_MS = longArrayOf(1000, 3000, 8000)

        private val INIT_THREAD_COUNTER = AtomicInteger()
        private val INIT_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
        ) { r -> daemon(r, "MediaPlayer-init-${INIT_THREAD_COUNTER.incrementAndGet()}") }
    }

    private val debugLabel: String = "${displayScreen.uuid}/${Integer.toHexString(System.identityHashCode(this))}"

    private val terminated = AtomicBoolean(false)
    private val restartPending = AtomicBoolean(false)

    private val clock = PlaybackClock()
    private val audio = AudioSink(debugLabel)
    private val video = VideoFramePipe(debugLabel)

    private val controlExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { daemon(it, "MediaPlayer-ctrl") }

    private val fitTextureTask: Runnable = Runnable { displayScreen.fitTexture() }

    @Volatile private var statsExecutor: ScheduledExecutorService? = null
    @Volatile private var watchdogExecutor: ScheduledExecutorService? = null

    @Volatile private var availableVideoStreams: List<YtStream>? = null
    @Volatile private var availableAudioStreams: List<YtStream>? = null
    @Volatile private var currentVideoStream: YtStream? = null
    @Volatile private var currentAudioStream: YtStream? = null

    @Volatile private var _initialized = false
    private val initCallbacks = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var liveStream = false
    @Volatile private var seekable = false
    @Volatile private var durationHintNanos = 0L
    private var lastQuality = 0

    @Volatile private var fetchRetries = 0

    @Volatile private var videoProcess: Process? = null
    @Volatile private var audioProcess: Process? = null
    @Volatile private var videoThread: Thread? = null
    @Volatile private var audioThread: Thread? = null
    @Volatile private var videoStopFlag: AtomicBoolean? = null
    @Volatile private var audioStopFlag: AtomicBoolean? = null

    @Volatile private var playing = false

    @Volatile private var userVolume = Initializer.config.defaultDisplayVolume
    @Volatile private var lastAttenuation = 1.0
    @Volatile private var brightness = 1.0

    init {
        INIT_EXECUTOR.submit { initialize() }
    }

    /** Resumes playback from the current seek position. No-op if already playing. */
    fun play() = safeExecute(::doPlay)

    /** Pauses playback, capturing the current position for later resume. */
    fun pause() = safeExecute(::doPause)

    /** Stops playback permanently; the instance must not be used after this call. */
    fun stop() {
        if (terminated.getAndSet(true)) return
        val stopFuture = if (!controlExecutor.isShutdown) {
            runCatching { controlExecutor.submit(::doStop) }.getOrNull()
        } else null
        if (stopFuture != null) {
            runCatching { stopFuture.get(STOP_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .onFailure { doStop() }
        } else {
            doStop()
        }
        controlExecutor.shutdownNow()
    }

    /** Seeks to an absolute position in nanos. [fire] triggers [DisplayScreen.afterSeek]. */
    fun seekTo(nanos: Long, fire: Boolean) = safeExecute { doSeek(nanos, fire) }

    /** Seeks [s] seconds relative to the current position. */
    // TODO: rewrite this
    fun seekRelative(s: Double) = safeExecute {
        if (!_initialized || !seekable) return@safeExecute
        val tgt = (getCurrentTime() + (s * 1e9).toLong()).coerceAtLeast(0)
        val dur = (getDuration() - 1).coerceAtLeast(0)
        if (dur <= 0) return@safeExecute
        doSeek(minOf(tgt, dur), true)
    }

    /** Current playback position in nanos. Falls back to seek offset when paused or not started. */
    fun getCurrentTime(): Long {
        if (!_initialized || !playing) return clock.seekOffsetNanos
        return clock.currentTime()
    }

    /** Stream duration in nanos, or 0 for live streams. */
    fun getDuration(): Long = if (liveStream) 0L else durationHintNanos

    /** Returns true once stream selection is complete and playback has started. */
    fun isInitialized(): Boolean = _initialized

    /**
     * Runs [callback] immediately if already initialized, otherwise queues it to run once
     * initialization completes. The callback is called on the init thread.
     */
    fun whenInitialized(callback: () -> Unit) {
        if (_initialized) { callback(); return }
        initCallbacks.add(callback)
        if (_initialized && initCallbacks.remove(callback)) callback()
    }

    /**
     * Returns true if the selected stream is a livestream. Livestreams start playing immediately
     * and may not support seeking. Note that this is based on `yt-dlp`'s metadata and may not be perfectly reliable.
     */
    fun isLive(): Boolean = liveStream

    /** Returns true if the selected stream supports seeking. Note that some livestreams may be seekable. */
    fun canSeek(): Boolean = _initialized && seekable

    /** Returns true if the media clock is running (i.e. playback has started and the first frame has arrived). */
    fun isClockRunning(): Boolean = clock.isRunning

    /** Sets the user-controlled volume (0.0-2.0). Distance attenuation is applied on top. */
    fun setVolume(volume: Float) {
        userVolume = volume.toDouble().coerceIn(0.0, 2.0)
        audio.currentVolume = userVolume * lastAttenuation
    }

    /** Sets the brightness multiplier applied to each frame before GPU upload (0.0-2.0). */
    fun setBrightness(brightness: Float) {
        this.brightness = brightness.toDouble().coerceIn(0.0, 2.0)
    }

    /** True once the first frame has been decoded and is ready for GPU upload. */
    fun textureFilled(): Boolean = video.textureFilled()

    /** Uploads the latest decoded frame to [texture]. Must be called from the render thread. */
    fun updateFrame(texture: GpuTexture) {
        video.updateFrame(texture, displayScreen.textureWidth, displayScreen.textureHeight)
    }

    /** Returns the list of available video quality levels (in pixels) for the current stream. */
    fun getAvailableQualities(): List<Int> {
        val streams = availableVideoStreams ?: return emptyList()
        val cap = if (Initializer.isPremium) 2160 else 1080
        return streams.asSequence()
            .mapNotNull { it.resolution }
            .map { MediaStreamSelector.parseQualityValue(it, Int.MAX_VALUE) }
            .filter { it != Int.MAX_VALUE && it <= cap }
            .distinct()
            .sorted()
            .toList()
    }

    /** Switches to the closest available stream for [quality] (e.g. "720p"). */
    fun setQuality(quality: String) = safeExecute { changeQuality(quality) }

    /**
     * Updates distance-based volume attenuation. Call every tick from the game thread.
     *
     * @param playerPos  player block position
     * @param maxRadius  radius beyond which the screen is silent
     */
    fun tick(playerPos: BlockPos, maxRadius: Double) {
        if (!_initialized) return
        val dist = displayScreen.getDistanceToScreen(playerPos)
        val attenuation = (1.0 - minOf(1.0, dist / maxRadius)).let { it * it }
        if (abs(attenuation - lastAttenuation) > 1e-5) {
            lastAttenuation = attenuation
            audio.currentVolume = userVolume * attenuation
        }
    }

    /**
     * Initializes the media player by extracting stream info with `yt-dlp`, picking the best
     * audio and video streams, and starting playback. This runs on a background thread and may be retried
     * automatically on failure with exponential backoff. The player is marked as errored if initialization ultimately
     * fails, which triggers an error message on the display screen.
     */
    private fun initialize() {
        var success = false
        try {
            val videoId = GeneralUtil.extractVideoId(youtubeUrl)
            if (videoId.isNullOrEmpty()) {
                LoggingManager.error("[MediaPlayer] Could not extract video ID from URL: $youtubeUrl.")
                displayScreen.errored = true
                return
            }
            if (FFmpegBinary.getPath() == null) {
                LoggingManager.error("[MediaPlayer] FFmpeg binary not available.")
                displayScreen.errored = true
                return
            }

            val cleanUrl = "https://www.youtube.com/watch?v=$videoId"
            val all = YtDlp.fetch(cleanUrl)
            if (terminated.get()) return
            if (all.isEmpty()) {
                LoggingManager.error("[MediaPlayer] No streams available for $cleanUrl.")
                displayScreen.errored = true
                return
            }

            liveStream = all.any(YtStream::isLive)
            seekable = !liveStream && all.any(YtStream::isSeekable)
            durationHintNanos = all.maxOfOrNull(YtStream::durationNanos) ?: 0L

            val videoStreams = all.filter(YtStream::hasVideo)
            val audioStreams = all.filter(YtStream::hasAudio)
            availableVideoStreams = videoStreams
            availableAudioStreams = audioStreams

            val requestedQuality = MediaStreamSelector.parseQualityValue(displayScreen.quality, 720)
            val pickedVideo = MediaStreamSelector.pickVideo(videoStreams, requestedQuality) ?: videoStreams.firstOrNull()
            val pickedAudio = MediaStreamSelector.pickAudio(audioStreams, lang, pickedVideo)
            if (pickedVideo == null || pickedAudio == null) {
                LoggingManager.error("[MediaPlayer] No usable streams for $cleanUrl.")
                displayScreen.errored = true
                return
            }

            currentVideoStream = pickedVideo
            currentAudioStream = pickedAudio
            lastQuality = MediaStreamSelector.parseQuality(pickedVideo)
            fetchRetries = 0
            _initialized = true
            success = true

            if (DEBUG) {
                LoggingManager.info("[MediaPlayer $debugLabel] video=$pickedVideo audio=$pickedAudio")
                LoggingManager.info("[MediaPlayer $debugLabel] live=$liveStream seekable=$seekable dur=$durationHintNanos")
                startStatsReporter()
            }

            safeExecute {
                if (!terminated.get()) startStreams(pickedVideo, pickedAudio, 0)
            }
        } catch (e: Exception) {
            LoggingManager.error("[MediaPlayer] Failed to initialize MediaPlayer", e)
            displayScreen.errored = true
        } finally {
            drainInitCallbacks(run = success)
        }
    }

    /**
     * Starts `FFmpeg` processes for the given video and audio streams, with an initial seek offset.
     * Also starts threads to read from the processes and feed the video frames and audio samples into the
     * [VideoFramePipe] and [AudioSink], respectively. If the player is already playing, it is stopped first.
     * This should only be called from the control executor thread..
     */
    private fun startStreams(pickedVideo: YtStream, pickedAudio: YtStream, offsetNanos: Long) {
        if (terminated.get()) return
        stopStreams()

        val ffmpeg = FFmpegBinary.getPath() ?: run { displayScreen.errored = true; return }

        clock.reset(offsetNanos)

        val q = if (lastQuality > 0) lastQuality else MediaStreamSelector.parseQuality(pickedVideo)
        val (frameW, frameH) = run {
            val tw = displayScreen.textureWidth
            val th = displayScreen.textureHeight
            if (tw > 0 && th > 0) tw to th
            else MediaStreamSelector.qualityToDims(q).let { it[0] to it[1] }
        }

        if (DEBUG) {
            LoggingManager.info("[MediaPlayer $debugLabel] starting FFmpeg ${frameW}x${frameH} offset=${offsetNanos / 1_000_000L} ms.")
        }

        try {
            val vp = MediaProcess.buildVideo(ffmpeg, pickedVideo.url, frameW, frameH, offsetNanos)
            val ap = MediaProcess.buildAudio(ffmpeg, pickedAudio.url, offsetNanos, AudioSink.SAMPLE_RATE)
            videoProcess = vp
            audioProcess = ap

            val vStop = AtomicBoolean(false)
            val aStop = AtomicBoolean(false)
            videoStopFlag = vStop
            audioStopFlag = aStop

            videoThread = video.start(
                proc = vp,
                w = frameW,
                h = frameH,
                seekOffsetNanos = offsetNanos,
                sourceFps = pickedVideo.fps ?: 30.0,
                stopFlag = vStop,
                terminated = terminated,
                getAudioClock = { clock.audioClockNanos(audio.framePosition, AudioSink.SAMPLE_RATE) },
                onFirstFrame = { clock.markFirstFrame() },
                getBrightness = { brightness },
                onEos = { stderr, normalEos -> handleStreamEnd(stderr, normalEos) },
                fitTexture = fitTextureTask,
            )
            audioThread = audio.start(ap, terminated, aStop)

            playing = true
            startWatchdog()
        } catch (e: IOException) {
            LoggingManager.error("[MediaPlayer $debugLabel] Failed to start FFmpeg", e)
            displayScreen.errored = true
        }
    }

    /**
     * Stops the `FFmpeg` processes and waits for the threads to finish. Safe to call multiple times and from any thread.
     */
    private fun stopStreams() {
        playing = false
        stopWatchdog()

        val vp = videoProcess; val ap = audioProcess
        val vt = videoThread; val at = audioThread
        val vStop = videoStopFlag; val aStop = audioStopFlag
        videoProcess = null; audioProcess = null
        videoThread = null; audioThread = null
        videoStopFlag = null; audioStopFlag = null

        vStop?.set(true); aStop?.set(true)
        MediaProcess.gracefulDestroy(vp)
        MediaProcess.gracefulDestroy(ap)
        audio.stop()
        joinSafely(vt); joinSafely(at)
    }

    /**
     * Handles the end of stream from the video thread, which may be triggered by normal EOS or by an error
     * (with stderr output).
     */
    private fun handleStreamEnd(stderr: String, normalEos: Boolean) {
        if (terminated.get()) return

        val is403or404 = stderr.contains("403") || stderr.contains("Forbidden")
                || stderr.contains("404") || stderr.contains("Not Found")
        val isTransient = MediaUtil.isTransientError(stderr)

        if (is403or404 && fetchRetries < MAX_FETCH_RETRIES) { scheduleRetry(true); return }
        if (isTransient && !is403or404 && fetchRetries < MAX_FETCH_RETRIES) { scheduleRetry(false); return }

        if (normalEos && liveStream && fetchRetries < MAX_FETCH_RETRIES) {
            LoggingManager.warn("[MediaPlayer $debugLabel] Live EOS, retrying...")
            scheduleRetry(true); return
        }

        if (normalEos && !liveStream) {
            if (restartPending.compareAndSet(false, true)) {
                safeExecute {
                    try {
                        val v = currentVideoStream; val a = currentAudioStream
                        if (!terminated.get() && !displayScreen.isPaused && v != null && a != null) {
                            clock.reset(0)
                            startStreams(v, a, 0)
                            displayScreen.afterSeek()
                        }
                    } finally {
                        restartPending.set(false)
                    }
                }
            }
            return
        }

        if (stderr.isNotEmpty()) {
            LoggingManager.error("[MediaPlayer $debugLabel] Unrecoverable stream error: ${MediaUtil.truncate(stderr)}.")
        }
        displayScreen.errored = true
    }

    /**
     * Schedules a retry of the initialization and stream startup after a delay, with optional cache invalidation for
     * 403 / 404 errors. The retry count is incremented and logged, and the player is marked as uninitialized to allow
     * the new streams to start properly.
     */
    private fun scheduleRetry(invalidateCache: Boolean) {
        val attempt = fetchRetries++
        val delayMs = RETRY_BACKOFF_MS[attempt.coerceAtMost(RETRY_BACKOFF_MS.lastIndex)]
        val reason = if (invalidateCache) "Cache invalidated" else "Transient error"
        LoggingManager.warn("[MediaPlayer $debugLabel] $reason — retry $fetchRetries/$MAX_FETCH_RETRIES in ${delayMs}ms.")
        if (invalidateCache) YtDlp.invalidateCache(youtubeUrl)
        _initialized = false
        INIT_EXECUTOR.submit {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@submit }
            if (!terminated.get()) initialize()
        }
    }

    /**
     * Starts playback by launching `FFmpeg` processes and reader threads if not already playing. No-op if already playing
     * or not initialized.
     */
    private fun doPlay() {
        if (!_initialized || terminated.get() || playing) return
        val v = currentVideoStream ?: return
        val a = currentAudioStream ?: return
        playing = true
        startStreams(v, a, clock.seekOffsetNanos)
    }

    /**
     * Pauses playback by stopping the `FFmpeg` processes and reader threads, and capturing the current playback position
     * from the audio clock if possible (falling back to the wall clock). No-op if already paused or not initialized.
     */
    private fun doPause() {
        if (!playing) return
        val pauseOffset = runCatching {
            val fp = audio.framePosition
            if (fp >= 0) clock.audioClockNanos(fp, AudioSink.SAMPLE_RATE) else -1L
        }.getOrDefault(-1L)
        clock.seekOffsetNanos = if (pauseOffset >= 0) pauseOffset else clock.currentTime()
        playing = false
        stopStreams()
    }

    /**
     * Stops playback and releases resources. This is called when the player is stopped permanently, and should not be called
     * more than once. It is safe to call from any thread, but is also called from the control executor thread when stopping,
     * so it should not submit new tasks to the executor.
     */
    private fun doStop() {
        _initialized = false
        video.clear()
        stopWatchdog()
        stopStatsReporter()
        stopStreams()
        currentVideoStream = null
        currentAudioStream = null
    }

    /**
     * Seeks to the given position in nanos by restarting the streams with the new seek offset. If [fire] is true, calls
     * [DisplayScreen.afterSeek] after seeking to allow the screen to reset any relevant state.
     * No-op if not initialized or not seekable.
     */
    private fun doSeek(nanos: Long, fire: Boolean) {
        if (!_initialized || !seekable) return
        val v = currentVideoStream ?: return
        val a = currentAudioStream ?: return
        clock.seekOffsetNanos = nanos
        if (playing) startStreams(v, a, nanos)
        if (fire) displayScreen.afterSeek()
    }

    /**
     * Changes to the closest available stream for the given quality (e.g. "720p") by restarting the streams with the
     * new video stream and the best matching audio stream.
     */
    private fun changeQuality(desired: String) {
        if (!_initialized) return
        val videoStreams = availableVideoStreams ?: return
        val currentVideo = currentVideoStream ?: return
        val currentAudio = currentAudioStream ?: return

        val target = MediaStreamSelector.parseQualityValue(desired, -1)
        if (target < 0 || target == lastQuality) return
        val best = MediaStreamSelector.pickVideo(videoStreams, target) ?: return
        if (best.url == currentVideo.url) return

        val chosenAudio = availableAudioStreams?.let {
            MediaStreamSelector.pickAudio(it, lang, best) ?: currentAudio
        } ?: currentAudio

        val pos = if (liveStream) 0L else getCurrentTime()
        currentVideoStream = best
        currentAudioStream = chosenAudio
        lastQuality = MediaStreamSelector.parseQuality(best)
        if (playing) startStreams(best, chosenAudio, pos) else clock.seekOffsetNanos = pos
    }

    /**
     * Starts a watchdog that checks every [WATCHDOG_CHECK_INTERVAL_MS] milliseconds whether frames have been received recently,
     * and if not, restarts the streams. This is to recover from stalls in `FFmpeg` or the network. The watchdog is stopped when
     * playback stops or the player is terminated.
     */
    private fun startWatchdog() {
        stopWatchdog()
        video.lastFrameReceivedNanos.set(System.nanoTime())
        val wd = Executors.newSingleThreadScheduledExecutor { daemon(it, "MediaPlayer-watchdog") }
        watchdogExecutor = wd
        wd.scheduleAtFixedRate({
            try {
                if (terminated.get() || !playing) return@scheduleAtFixedRate
                val last = video.lastFrameReceivedNanos.get()
                if (last == 0L) return@scheduleAtFixedRate
                val elapsed = System.nanoTime() - last
                if (elapsed > WATCHDOG_TIMEOUT_NS) {
                    LoggingManager.warn("[Watchdog $debugLabel] No frames for ${elapsed / 1_000_000L}ms. Restarting...")
                    video.lastFrameReceivedNanos.set(System.nanoTime())
                    safeExecute {
                        if (terminated.get()) return@safeExecute
                        val v = currentVideoStream; val a = currentAudioStream
                        if (v != null && a != null) startStreams(v, a, if (liveStream) 0L else clock.currentTime())
                    }
                }
            } catch (_: Throwable) {}
        }, WATCHDOG_CHECK_INTERVAL_MS, WATCHDOG_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    /** Stops the watchdog if it's running. */
    private fun stopWatchdog() { watchdogExecutor?.shutdownNow(); watchdogExecutor = null }

    /** Logs stats about decoding and playback, including input/output frame rates, dropped frames, current position, and
     * whether it's a live stream.
     */
    private fun reportStats() {
        try {
            val inN = samplesIn.getAndSet(0); val outN = framesToGpu.getAndSet(0); val dropN = framesDropped.getAndSet(0)
            val sec = STATS_INTERVAL_MS / 1000.0
            LoggingManager.info(
                String.format(
                    "[MediaPlayer %s] decode=%.1ffps gpu=%.1ffps dropped=%.1f/s pos=%dms live=%s",
                    debugLabel, inN / sec, outN / sec, dropN / sec, getCurrentTime() / 1_000_000L, liveStream,
                )
            )
        } catch (_: Throwable) {}
    }

    /** Starts a stats reporter that logs decoding and playback stats every [STATS_INTERVAL_MS] milliseconds. */
    private fun startStatsReporter() {
        if (statsExecutor != null) return
        statsExecutor = Executors.newSingleThreadScheduledExecutor { daemon(it, "MediaPlayer-stats") }.also {
            it.scheduleAtFixedRate(::reportStats, STATS_INTERVAL_MS, STATS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Stops the stats reporter if it's running.
     */
    private fun stopStatsReporter() { statsExecutor?.shutdownNow(); statsExecutor = null }

    /** Drains the init callbacks queue and runs the callbacks if [run] is true. This is called at the end of
     * initialization, and ensures that any callbacks added during initialization are also run.
     */
    private fun drainInitCallbacks(run: Boolean) {
        val snapshot = initCallbacks.toList()
        initCallbacks.clear()
        if (run) snapshot.forEach { it() }
    }

    /** Submits [action] to the control executor if the player is not terminated and the executor is still running.
     * This is used to serialize access to the player's state and ensure that actions are not performed after termination.
     */
    private fun safeExecute(action: () -> Unit) {
        if (!terminated.get() && !controlExecutor.isShutdown) {
            runCatching { controlExecutor.submit(action) }
        }
    }
}
