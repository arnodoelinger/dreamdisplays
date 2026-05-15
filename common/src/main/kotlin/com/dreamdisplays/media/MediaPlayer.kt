package com.dreamdisplays.media

import com.dreamdisplays.Initializer
import com.dreamdisplays.display.DisplayScreen
import com.dreamdisplays.ffmpeg.FFmpegBinary
import com.dreamdisplays.util.GeneralUtil
import com.dreamdisplays.ytdlp.YtDlp
import com.dreamdisplays.ytdlp.YtStream
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import me.inotsleep.utils.logging.LoggingManager
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine

/**
 * Media player for a single `YouTube` video. It handles stream selection, manages `FFmpeg` processes for video and audio,
 * reads raw frames and audio. It's a main orchestrator for video / audio playback.
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

        private const val STOP_WAIT_TIMEOUT_SECONDS = 3L
        private const val STATS_INTERVAL_MS = 2000L
        private const val MAX_FETCH_RETRIES = 3
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHUNK_BYTES = AUDIO_SAMPLE_RATE * 2 * 2 / 20
        private const val AUDIO_LINE_BUFFER_BYTES = AUDIO_CHUNK_BYTES * 10
        private const val CLOCK_NOT_STARTED = Long.MIN_VALUE
        private const val WATCHDOG_TIMEOUT_NS = 30_000_000_000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 5000L
        private const val AUDIO_LINE_OPEN_RETRIES = 3
        private const val AUDIO_LINE_RETRY_DELAY_MS = 200L
        private const val VIDEO_FPS = 30.0
        private const val VIDEO_FRAME_NS = (1_000_000_000.0 / VIDEO_FPS).toLong()

        private const val VIDEO_SYNC_THRESHOLD_NS = 10_000_000L // 10 ms
        private const val VIDEO_DROP_THRESHOLD_NS = 80_000_000L // 80 ms
        private val RETRY_BACKOFF_MS = longArrayOf(1000, 3000, 8000)

        private val INIT_THREAD_COUNTER = AtomicInteger()
        private val INIT_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(
            (Runtime.getRuntime().availableProcessors()).coerceIn(2, 4),
        ) { r -> daemon(r, "MediaPlayer-init-${INIT_THREAD_COUNTER.incrementAndGet()}") }

        private fun daemon(r: Runnable, name: String): Thread =
            Thread(r, name).apply { isDaemon = true }
    }

    private val debugLabel: String =
        "${displayScreen.uuid}/${Integer.toHexString(System.identityHashCode(this))}"

    private val terminated = AtomicBoolean(false)
    private val restartPending = AtomicBoolean(false)

    private val samplesIn = AtomicLong()
    private val framesToGpu = AtomicLong()
    private val framesDropped = AtomicLong()
    private val lastFrameReceivedNanos = AtomicLong(0)

    private val controlExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { daemon(it, "MediaPlayer-ctrl") }

    // Posted to the Minecraft render queue from the video reader thread.
    // Cached as a single instance to avoid per-frame allocation.
    private val fitTextureTask: Runnable = Runnable { displayScreen.fitTexture() }

    @Volatile private var statsExecutor: ScheduledExecutorService? = null
    @Volatile private var watchdogExecutor: ScheduledExecutorService? = null

    @Volatile private var availableVideoStreams: List<YtStream>? = null
    @Volatile private var availableAudioStreams: List<YtStream>? = null
    @Volatile private var currentVideoStream: YtStream? = null
    @Volatile private var currentAudioStream: YtStream? = null

    @Volatile private var _initialized = false
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
    @Volatile private var currentAudioLine: SourceDataLine? = null
    @Volatile private var playing = false
    @Volatile private var seekOffsetNanos = 0L
    @Volatile private var startWallNanos = CLOCK_NOT_STARTED

    // Frame pipeline: producer-consumer with two pre-allocated direct buffers.
    // The reader thread fills the "spare" buffer, then atomically swaps it
    // into `readyBufferRef`; the render thread reads from `readyBufferRef`.
    private val readyBufferRef = AtomicReference<ByteBuffer?>(null)
    private val frameAvailable = AtomicBoolean(false)
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0

    @Volatile private var userVolume = Initializer.config.defaultDisplayVolume
    @Volatile private var lastAttenuation = 1.0
    @Volatile private var currentVolume = Initializer.config.defaultDisplayVolume
    @Volatile private var brightness = 1.0

    init {
        INIT_EXECUTOR.submit { initialize() }
    }

    fun play() = safeExecute(::doPlay)

    fun pause() = safeExecute(::doPause)

    fun stop() {
        if (terminated.getAndSet(true)) return
        val stopFuture = if (!controlExecutor.isShutdown) {
            runCatching { controlExecutor.submit(::doStop) }.getOrNull()
        } else null
        if (stopFuture != null) {
            try {
                stopFuture.get(STOP_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: Exception) {
                doStop()
            }
        } else {
            doStop()
        }
        controlExecutor.shutdownNow()
    }

    fun seekTo(nanos: Long, fire: Boolean) = safeExecute { doSeek(nanos, fire) }

    fun seekRelative(s: Double) = safeExecute {
        if (!_initialized || !seekable) return@safeExecute
        val tgt = (getCurrentTime() + (s * 1e9).toLong()).coerceAtLeast(0)
        val dur = (getDuration() - 1).coerceAtLeast(0)
        if (dur <= 0) return@safeExecute
        doSeek(minOf(tgt, dur), true)
    }

    fun getCurrentTime(): Long {
        if (!_initialized || !playing) return seekOffsetNanos
        val start = startWallNanos
        return if (start == CLOCK_NOT_STARTED) seekOffsetNanos
        else seekOffsetNanos + (System.nanoTime() - start)
    }

    fun getDuration(): Long = if (liveStream) 0L else durationHintNanos

    fun isInitialized(): Boolean = _initialized

    fun isLive(): Boolean = liveStream

    fun canSeek(): Boolean = _initialized && seekable

    fun isClockRunning(): Boolean = startWallNanos != CLOCK_NOT_STARTED

    private fun getAudioClockNanos(): Long {
        val line = currentAudioLine ?: return seekOffsetNanos

        return seekOffsetNanos +
                (line.longFramePosition * 1_000_000_000L / AUDIO_SAMPLE_RATE)
    }

    fun setVolume(volume: Float) {
        userVolume = volume.toDouble().coerceIn(0.0, 2.0)
        currentVolume = userVolume * lastAttenuation
    }

    fun setBrightness(brightness: Float) {
        this.brightness = brightness.toDouble().coerceIn(0.0, 2.0)
    }

    fun textureFilled(): Boolean = readyBufferRef.get() != null

    fun updateFrame(texture: GpuTexture) {
        if (!frameAvailable.compareAndSet(true, false)) return
        val buf = readyBufferRef.get() ?: return
        val w = displayScreen.textureWidth
        val h = displayScreen.textureHeight
        if (w != frameW || h != frameH) return
        buf.rewind()
        if (!texture.isClosed) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                texture, buf, NativeImage.Format.RGBA,
                0, 0, 0, 0, texture.getWidth(0), texture.getHeight(0),
            )
        }
        if (DEBUG) framesToGpu.incrementAndGet()
    }

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

    fun setQuality(quality: String) = safeExecute { changeQuality(quality) }

    fun tick(playerPos: BlockPos, maxRadius: Double) {
        if (!_initialized) return
        val dist = displayScreen.getDistanceToScreen(playerPos)
        val attenuation = (1.0 - minOf(1.0, dist / maxRadius)).let { it * it }
        if (kotlin.math.abs(attenuation - lastAttenuation) > 1e-5) {
            lastAttenuation = attenuation
            currentVolume = userVolume * attenuation
        }
    }

    private fun initialize() {
        try {
            val videoId = GeneralUtil.extractVideoId(youtubeUrl)
            if (videoId.isNullOrEmpty()) {
                LoggingManager.error("Could not extract video ID from URL: $youtubeUrl")
                displayScreen.errored = true
                return
            }
            if (FFmpegBinary.getPath() == null) {
                LoggingManager.error("[MediaPlayer] FFmpeg binary not available")
                displayScreen.errored = true
                return
            }

            val cleanUrl = "https://www.youtube.com/watch?v=$videoId"
            val all = YtDlp.fetch(cleanUrl)
            if (terminated.get()) return
            if (all.isEmpty()) {
                LoggingManager.error("No streams available for $cleanUrl")
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
            val pickedVideo = MediaStreamSelector.pickVideo(videoStreams, requestedQuality)
                ?: videoStreams.firstOrNull()
            val pickedAudio = MediaStreamSelector.pickAudio(audioStreams, lang, pickedVideo)
            if (pickedVideo == null || pickedAudio == null) {
                LoggingManager.error("No usable streams for $cleanUrl")
                displayScreen.errored = true
                return
            }

            currentVideoStream = pickedVideo
            currentAudioStream = pickedAudio
            lastQuality = MediaStreamSelector.parseQuality(pickedVideo)
            fetchRetries = 0
            _initialized = true

            if (DEBUG) {
                LoggingManager.info("[MP $debugLabel] video=$pickedVideo audio=$pickedAudio")
                LoggingManager.info("[MP $debugLabel] live=$liveStream seekable=$seekable dur=$durationHintNanos")
                startStatsReporter()
            }

            safeExecute {
                if (!terminated.get()) startStreams(pickedVideo, pickedAudio, 0)
            }
        } catch (e: Exception) {
            LoggingManager.error("Failed to initialize MediaPlayer", e)
            displayScreen.errored = true
        }
    }

    private fun startStreams(video: YtStream, audio: YtStream, offsetNanos: Long) {
        if (terminated.get()) return
        stopStreams()

        val ffmpeg = FFmpegBinary.getPath() ?: run {
            displayScreen.errored = true
            return
        }

        seekOffsetNanos = offsetNanos
        startWallNanos = CLOCK_NOT_STARTED

        val q = if (lastQuality > 0) lastQuality else MediaStreamSelector.parseQuality(video)
        val (frameW, frameH) = run {
            val tw = displayScreen.textureWidth
            val th = displayScreen.textureHeight
            if (tw > 0 && th > 0) tw to th
            else MediaStreamSelector.qualityToDims(q).let { it[0] to it[1] }
        }
        this.frameW = frameW
        this.frameH = frameH

        try {
            if (DEBUG) {
                LoggingManager.info("[MP $debugLabel] starting ffmpeg ${frameW}x${frameH} offset=${offsetNanos / 1_000_000L}ms")
            }
            val vp = MediaProcess.buildVideo(ffmpeg, video.url, frameW, frameH, offsetNanos)
            val ap = MediaProcess.buildAudio(ffmpeg, audio.url, offsetNanos, AUDIO_SAMPLE_RATE)
            videoProcess = vp
            audioProcess = ap

            val vStop = AtomicBoolean(false)
            val aStop = AtomicBoolean(false)
            videoStopFlag = vStop
            audioStopFlag = aStop

            videoThread = daemon({ videoReaderLoop(vp, frameW, frameH, vStop) }, "MediaPlayer-video").also { it.start() }
            audioThread = daemon({ audioReaderLoop(ap, aStop) }, "MediaPlayer-audio").also { it.start() }
            playing = true
            startWatchdog()
        } catch (e: IOException) {
            LoggingManager.error("[MediaPlayer $debugLabel] Failed to start ffmpeg", e)
            displayScreen.errored = true
        }
    }

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

        frameAvailable.set(false)
        readyBufferRef.set(null)

        joinSafely(vt); joinSafely(at)
    }

    private fun joinSafely(t: Thread?) {
        if (t != null && t != Thread.currentThread()) {
            try { t.join(2000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun videoReaderLoop(proc: Process, w: Int, h: Int, stopFlag: AtomicBoolean) {
        val frameSize = w * h * 4
        val frameData = ByteArray(frameSize)

        val bufA = ByteBuffer.allocateDirect(frameSize).order(ByteOrder.nativeOrder())
        val bufB = ByteBuffer.allocateDirect(frameSize).order(ByteOrder.nativeOrder())
        var spare: ByteBuffer = bufA

        var firstFrameLogged = false
        var videoPts = seekOffsetNanos

        val stderrBuf = StringBuilder()
        val stderrThread = daemon({
            try {
                BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                    r.lineSequence().forEach { line ->
                        synchronized(stderrBuf) { stderrBuf.append(line).append('\n') }
                        if (MediaUtils.isInterestingStderr(line)) {
                            LoggingManager.warn("[ffmpeg-v $debugLabel] $line")
                        }
                    }
                }
            } catch (_: IOException) {}
        }, "MediaPlayer-vstderr").also { it.start() }

        var normalEos = false
        val mc = Minecraft.getInstance()

        try {
            proc.inputStream.use { input ->
                while (!terminated.get() && !stopFlag.get()) {
                    val n = MediaUtils.readFull(input, frameData, frameSize)
                    if (n < frameSize) { normalEos = true; break }

                    lastFrameReceivedNanos.set(System.nanoTime())
                    if (!firstFrameLogged) {
                        firstFrameLogged = true
                        startWallNanos = System.nanoTime()
                        if (DEBUG) LoggingManager.info("[MP $debugLabel] first frame ${w}x${h}")
                    }

                    val diff = videoPts - getAudioClockNanos()
                    if (diff > VIDEO_SYNC_THRESHOLD_NS) LockSupport.parkNanos(diff)
                    if (diff < -VIDEO_DROP_THRESHOLD_NS) {
                        if (DEBUG) framesDropped.incrementAndGet()
                        videoPts += VIDEO_FRAME_NS
                        continue
                    }

                    if (!captureSamples) {
                        videoPts += VIDEO_FRAME_NS
                        continue
                    }

                    if (brightness != 1.0) {
                        MediaBufferEffects.applyBrightness(frameData, frameSize, brightness)
                    }

                    spare.clear()
                    spare.put(frameData, 0, frameSize)
                    spare.flip()

                    val prev = readyBufferRef.getAndSet(spare)
                    spare = prev ?: if (spare === bufA) bufB else bufA
                    frameAvailable.set(true)

                    if (DEBUG) samplesIn.incrementAndGet()
                    mc.execute(fitTextureTask)

                    videoPts += VIDEO_FRAME_NS
                }
            }
        } catch (e: IOException) {
            if (DEBUG && !terminated.get() && !stopFlag.get()) {
                LoggingManager.warn("[MP $debugLabel] video read: ${e.message}")
            }
        }

        var exitCode = -1

        if (normalEos) {

            try {

                val done = proc.waitFor(500, TimeUnit.MILLISECONDS)

                exitCode = if (done) proc.exitValue() else -1

                if (!done) {
                    proc.destroyForcibly()
                }

            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        if (!terminated.get() && !stopFlag.get()) {

            try {
                stderrThread.join(500)
            } catch (_: InterruptedException) {
            }

            val stderr = synchronized(stderrBuf) {
                stderrBuf.toString()
            }

            handleStreamEnd(stderr, exitCode == 0)
        }
    }

    private fun audioReaderLoop(proc: Process, stopFlag: AtomicBoolean) {
        daemon({
            try { proc.errorStream.transferTo(OutputStream.nullOutputStream()) }
            catch (_: IOException) {}
        }, "MediaPlayer-astderr").also { it.start() }

        var line: SourceDataLine? = null
        try {
            proc.inputStream.use { input ->
                val fmt = AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    AUDIO_SAMPLE_RATE.toFloat(), 16, 2, 4, AUDIO_SAMPLE_RATE.toFloat(), false,
                )
                val info = DataLine.Info(SourceDataLine::class.java, fmt)
                if (!AudioSystem.isLineSupported(info)) {
                    LoggingManager.warn("[MediaPlayer $debugLabel] javax.sound: PCM line not supported")
                    return
                }
                line = openAudioLine(info, fmt) ?: return

                // wait for first video frame to arrive (or give up after 5s)
                val deadline = System.nanoTime() + 5_000_000_000L
                while (startWallNanos == CLOCK_NOT_STARTED
                    && !terminated.get() && !stopFlag.get()
                    && System.nanoTime() < deadline) {
                    LockSupport.parkNanos(10_000_000L)
                }
                if (terminated.get() || stopFlag.get()) return
                line.start()
                currentAudioLine = line

                val chunk = ByteArray(AUDIO_CHUNK_BYTES)
                while (!terminated.get() && !stopFlag.get()) {
                    val n = MediaUtils.readFull(input, chunk, AUDIO_CHUNK_BYTES)
                    if (n <= 0) break
                    MediaBufferEffects.applyVolumeS16LE(chunk, n, currentVolume)
                    var written = 0
                    while (written < n && !terminated.get() && !stopFlag.get()) {
                        val w = line.write(chunk, written, n - written)
                        if (w <= 0) break
                        written += w
                    }
                }
            }
        } catch (e: IOException) {
            if (DEBUG && !terminated.get() && !stopFlag.get()) {
                LoggingManager.warn("[MP $debugLabel] audio read: ${e.message}")
            }
        } catch (e: Exception) {
            if (!terminated.get() && !stopFlag.get()) {
                LoggingManager.warn("[MP $debugLabel] audio pipeline: ${e.message}")
            }
        } finally {
            line?.let {
                currentAudioLine = null
                runCatching { it.flush() }
                runCatching { it.stop() }
                runCatching { it.close() }
            }
        }
    }

    private fun openAudioLine(info: DataLine.Info, fmt: AudioFormat): SourceDataLine? {
        repeat(AUDIO_LINE_OPEN_RETRIES) { attempt ->
            try {
                return (AudioSystem.getLine(info) as SourceDataLine).also {
                    it.open(fmt, AUDIO_LINE_BUFFER_BYTES)
                }
            } catch (e: LineUnavailableException) {
                if (attempt == AUDIO_LINE_OPEN_RETRIES - 1) {
                    LoggingManager.warn("[MediaPlayer $debugLabel] javax.sound: line unavailable: ${e.message}")
                    return null
                }
                try { Thread.sleep(AUDIO_LINE_RETRY_DELAY_MS * (attempt + 1)) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return null }
            }
        }
        return null
    }

    private fun handleStreamEnd(stderr: String, normalEos: Boolean) {
        if (terminated.get()) return

        val is403or404 = stderr.contains("403") || stderr.contains("Forbidden")
                || stderr.contains("404") || stderr.contains("Not Found")
        val isTransient = MediaUtils.isTransientError(stderr)

        if (is403or404 && fetchRetries < MAX_FETCH_RETRIES) { scheduleRetry(true); return }
        if (isTransient && !is403or404 && fetchRetries < MAX_FETCH_RETRIES) { scheduleRetry(false); return }

        if (normalEos && liveStream && fetchRetries < MAX_FETCH_RETRIES) {
            LoggingManager.warn("[MediaPlayer $debugLabel] live EOS, retrying")
            scheduleRetry(true)
            return
        }

        if (normalEos && !liveStream) {
            if (restartPending.compareAndSet(false, true)) {
                safeExecute {
                    try {
                        val video = currentVideoStream
                        val audio = currentAudioStream
                        if (!terminated.get() && !displayScreen.getPaused() && video != null && audio != null) {
                            seekOffsetNanos = 0
                            startStreams(video, audio, 0)
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
            LoggingManager.error("[MediaPlayer $debugLabel] unrecoverable stream error: ${MediaUtils.truncate(stderr)}")
        }
        displayScreen.errored = true
    }

    private fun scheduleRetry(invalidateCache: Boolean) {
        val attempt = fetchRetries++
        val delayMs = RETRY_BACKOFF_MS[attempt.coerceAtMost(RETRY_BACKOFF_MS.lastIndex)]
        val reason = if (invalidateCache) "cache invalidated" else "transient error"
        LoggingManager.warn("[MediaPlayer $debugLabel] $reason — retry $fetchRetries/$MAX_FETCH_RETRIES in ${delayMs}ms")
        if (invalidateCache) YtDlp.invalidateCache(youtubeUrl)
        _initialized = false
        INIT_EXECUTOR.submit {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@submit }
            if (!terminated.get()) initialize()
        }
    }

    private fun doPlay() {
        if (!_initialized || terminated.get() || playing) return
        val video = currentVideoStream ?: return
        val audio = currentAudioStream ?: return
        playing = true
        startStreams(video, audio, seekOffsetNanos)
    }

    private fun doPause() {
        if (!playing) return
        val ln = currentAudioLine
        val pauseOffset = if (ln != null) {
            runCatching {
                seekOffsetNanos + ln.longFramePosition * 1_000_000_000L / AUDIO_SAMPLE_RATE
            }.getOrDefault(-1L)
        } else -1L
        seekOffsetNanos = if (pauseOffset >= 0) pauseOffset else getCurrentTime()
        playing = false
        stopStreams()
    }

    private fun doStop() {
        _initialized = false
        frameAvailable.set(false)
        readyBufferRef.set(null)
        stopWatchdog()
        stopStatsReporter()
        stopStreams()
        currentVideoStream = null
        currentAudioStream = null
    }

    private fun doSeek(nanos: Long, fire: Boolean) {
        if (!_initialized || !seekable) return
        val video = currentVideoStream ?: return
        val audio = currentAudioStream ?: return

        val wasPlaying = playing
        frameAvailable.set(false)
        readyBufferRef.set(null)
        Minecraft.getInstance().execute(displayScreen::reloadTexture)

        seekOffsetNanos = nanos
        if (wasPlaying) startStreams(video, audio, nanos)
        if (fire) displayScreen.afterSeek()
    }

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
        Minecraft.getInstance().execute(displayScreen::reloadTexture)
        currentVideoStream = best
        currentAudioStream = chosenAudio
        lastQuality = MediaStreamSelector.parseQuality(best)
        if (playing) startStreams(best, chosenAudio, pos) else seekOffsetNanos = pos
    }

    private fun startWatchdog() {
        stopWatchdog()
        lastFrameReceivedNanos.set(System.nanoTime())
        val wd = Executors.newSingleThreadScheduledExecutor { daemon(it, "MediaPlayer-watchdog") }
        watchdogExecutor = wd
        wd.scheduleAtFixedRate({
            try {
                if (terminated.get() || !playing) return@scheduleAtFixedRate
                val lastFrame = lastFrameReceivedNanos.get()
                if (lastFrame == 0L) return@scheduleAtFixedRate
                val elapsed = System.nanoTime() - lastFrame
                if (elapsed > WATCHDOG_TIMEOUT_NS) {
                    LoggingManager.warn("[MediaPlayer $debugLabel] watchdog: no frames for ${elapsed / 1_000_000L}ms — restarting")
                    lastFrameReceivedNanos.set(System.nanoTime())
                    safeExecute {
                        if (terminated.get()) return@safeExecute
                        val video = currentVideoStream
                        val audio = currentAudioStream
                        if (video != null && audio != null) {
                            val pos = if (liveStream) 0L else getCurrentTime()
                            startStreams(video, audio, pos)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }, WATCHDOG_CHECK_INTERVAL_MS, WATCHDOG_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopWatchdog() {
        watchdogExecutor?.shutdownNow()
        watchdogExecutor = null
    }

    private fun startStatsReporter() {
        if (statsExecutor != null) return
        statsExecutor = Executors.newSingleThreadScheduledExecutor { daemon(it, "MediaPlayer-stats") }.also {
            it.scheduleAtFixedRate(::reportStats, STATS_INTERVAL_MS, STATS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun stopStatsReporter() {
        statsExecutor?.shutdownNow()
        statsExecutor = null
    }

    private fun reportStats() {
        try {
            val inN = samplesIn.getAndSet(0)
            val outN = framesToGpu.getAndSet(0)
            val dropN = framesDropped.getAndSet(0)
            val sec = STATS_INTERVAL_MS / 1000.0
            LoggingManager.info(String.format(
                "[MP %s] decode=%.1ffps gpu=%.1ffps dropped=%.1f/s pos=%dms live=%s",
                debugLabel, inN / sec, outN / sec, dropN / sec, getCurrentTime() / 1_000_000L, liveStream,
            ))
        } catch (_: Throwable) {}
    }

    private fun safeExecute(action: Runnable) {
        if (!terminated.get() && !controlExecutor.isShutdown) {
            runCatching { controlExecutor.submit(action) }
        }
    }
}
