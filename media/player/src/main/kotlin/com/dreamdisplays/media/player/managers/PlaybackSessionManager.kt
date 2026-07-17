package com.dreamdisplays.media.player.managers

import com.dreamdisplays.api.media.audio.AudioDspStage
import com.dreamdisplays.media.player.process.FFmpegBinary
import com.dreamdisplays.media.runtime.MediaHostGuard
import com.dreamdisplays.api.media.DreamMediaException
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.events.PlayerEvents
import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.dreamdisplays.media.player.pipeline.AudioSink
import com.dreamdisplays.media.player.pipeline.FramePipe
import com.dreamdisplays.media.player.pipeline.NativeVideoFramePipe
import com.dreamdisplays.media.player.pipeline.PlaybackClock
import com.dreamdisplays.media.player.pipeline.VideoFramePipe
import com.dreamdisplays.media.player.process.HlsAudioFeeder
import com.dreamdisplays.media.player.process.HwAccelBackend
import com.dreamdisplays.media.player.process.MediaProcess
import com.dreamdisplays.media.player.stream.MediaStreamSelector
import com.dreamdisplays.media.player.stream.ActiveStreams
import com.dreamdisplays.media.player.util.MediaUtil
import com.dreamdisplays.api.media.FramePixelFormat
import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.media.player.util.joinSafely
import com.dreamdisplays.api.media.player.GpuTextureRef
import com.dreamdisplays.api.media.player.RenderThreadExecutor
import com.dreamdisplays.api.media.player.FrameUploaderFactory
import org.slf4j.LoggerFactory
import kotlin.math.abs
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns and manages the lifecycle of one `FFmpeg` video + audio session: processes, reader threads,
 * stop flags, [AudioSink], and the video pipe(s).
 *
 * A quality switch runs a **second video channel in parallel** ([beginQualitySwitch]): the live
 * channel keeps decoding and rendering the current resolution while the incoming channel warms up
 * the new one against the same audio clock. The render thread promotes the incoming channel the
 * instant its first frame lands ([promoteIncoming]), so the picture never freezes — the old video
 * keeps moving right up to the swap. Audio and the clock are never touched by a quality switch.
 *
 * `MediaPlayer` calls [start] / [stop] and delegates rendering queries here. The [StreamWatchdog]
 * is coordinated by `MediaPlayer` externally.
 */
internal class PlaybackSessionManager(
    private val debugLabel: String,
    private val clock: PlaybackClock,
    private val events: PlayerEvents,
    private val terminated: AtomicBoolean,

    /** Returns the current GPU texture dimensions (width to height). */
    private val getTextureSize: () -> Pair<Int, Int>,
    private val getBrightness: () -> Double,

    /** Invoked by the live video channel when the stream ends or errors. Called on the reader thread. */
    private val onStreamEnd: (stderr: String, normalEos: Boolean) -> Unit,

    /**
     * Invoked when an in-flight quality switch fails before promotion, so the caller can drop the
     * staged texture. [appliedAnyway] is true when the target quality still takes effect through a
     * different path (a full restart using the same new stream set) despite the parallel handoff
     * itself failing — callers must not roll back quality metadata in that case.
     */
    private val onQualitySwitchAborted: (appliedAnyway: Boolean) -> Unit = {},

    /**
     * nvoked when the live audio process ends on its own (crash / broken pipe) instead of via a
     * deliberate [stop]. Unlike video, an audio failure alone doesn't stop frames from arriving, so
     * nothing else would ever notice the session went silent. Called on the audio reader thread.
     */
    private val onAudioFailure: (stderr: String) -> Unit = {},

    /** Runs render-thread (GL) cleanup work. */
    private val renderExecutor: RenderThreadExecutor,

    /** Creates per-channel GPU frame uploaders. */
    private val uploaderFactory: FrameUploaderFactory,

    /** Whether the GPU-side planar (I420) render path is active. */
    private val gpuYuvActive: Boolean,

    /** Optional per-display acoustics DSP stage; null keeps the legacy distance-gain-only pipeline. */
    private val audioStage: AudioDspStage? = null,
) {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/PlaybackSession")

    private companion object {
        /** Pacing cadence for replay-only video; PTS still drives pacing, this is only the fallback. */
        const val REPLAY_FPS = 30.0
    }

    /** The audio half of a session: process, reader thread, and stop flag. The process is null while a
     *  reappearance bridge is still playing its cached prelude (no live process attached yet). */
    private class AudioHalf(val process: Process?, val thread: Thread, val stop: AtomicBoolean)

    /**
     * One decode channel: its own video pipe (native or JVM) plus the process / thread / stop driving it.
     * The live channel and the incoming (quality-switch) channel are independent instances, so two
     * resolutions can decode at once during a handoff.
     */
    private inner class VideoChannel {
        val nativePipe: NativeVideoFramePipe? =
            if (NativeMedia.isAvailable) NativeVideoFramePipe(debugLabel, uploaderFactory, gpuYuvActive) else null
        private val jvmPipe: VideoFramePipe? =
            if (nativePipe == null) VideoFramePipe(debugLabel, uploaderFactory) else null
        val pipe: FramePipe = nativePipe ?: jvmPipe!!

        @Volatile
        var process: Process? = null

        @Volatile
        var thread: Thread? = null

        /** True when this channel decodes via the in-process libav path — the only path that supports a
         *  warm park (the others use an external `FFmpeg` process / pipe). */
        @Volatile
        var inProcess = false
            private set
        val stop = AtomicBoolean()

        /**
         * Launches the video decode (in-process libav, native `FFmpeg`, or pure-JVM `FFmpeg`, in that
         * order of preference) into this channel's pipe. Throws [IOException] when the native session
         * cannot be opened. Never touches audio or the clock. [parkFlag] is honored on every path: the
         * in-process decoder idles in place, and the process paths park via pipe back-pressure.
         */
        fun launch(
            ffmpeg: String, streamSet: ActiveStreams, w: Int, h: Int, offsetNanos: Long,
            hwAccel: HwAccelBackend, onFirstFrame: () -> Unit, onEos: (String, Boolean) -> Unit,
            getAudioClock: () -> Long = ::pacingClockNanos, parkFlag: AtomicBoolean? = null,
            presentPreview: Boolean = true,
        ) {
            // SSRF guard for the in-process libav path, which bypasses MediaProcess.baseCommand
            val safeUrl = MediaHostGuard.resolveSafeUrl(streamSet.currentVideo.url)
            val fps = streamSet.currentVideo.fps ?: 30.0
            val lavThread = if (nativePipe != null && NativeMedia.lavInProcessEnabled) {
                nativePipe.startInProcess(
                    url = safeUrl, w = w, h = h, seekOffsetNanos = offsetNanos,
                    sourceFps = fps, hwAccel = hwAccel, stopFlag = stop, terminated = terminated,
                    getAudioClock = getAudioClock, onFirstFrame = onFirstFrame,
                    getBrightness = getBrightness, onEos = onEos, parkFlag = parkFlag,
                    presentPreview = presentPreview,
                )
            } else null
            if (lavThread != null) {
                process = null; thread = lavThread; inProcess = true; return
            }
            if (nativePipe != null) {
                val nv12 = NativeMedia.nv12Enabled
                val transport =
                    if (nv12) MediaProcess.VideoTransport.RAW_NV12 else MediaProcess.VideoTransport.RAW_RGB24
                val args = MediaProcess.videoArgs(ffmpeg, safeUrl, w, h, offsetNanos, hwAccel, transport)
                val vt = nativePipe.start(
                    args = args, w = w, h = h, nv12 = nv12, seekOffsetNanos = offsetNanos, sourceFps = fps,
                    stopFlag = stop, terminated = terminated, getAudioClock = getAudioClock,
                    onFirstFrame = onFirstFrame, getBrightness = getBrightness, onEos = onEos,
                    parkFlag = parkFlag, presentPreview = presentPreview,
                ) ?: throw IOException("Native FFmpeg session failed to start")
                process = null; thread = vt; return
            }
            val vp = MediaProcess.buildVideo(ffmpeg, safeUrl, w, h, offsetNanos, hwAccel)
            val vt = jvmPipe!!.start(
                proc = vp, w = w, h = h, seekOffsetNanos = offsetNanos, sourceFps = fps,
                stopFlag = stop, terminated = terminated, getAudioClock = getAudioClock,
                onFirstFrame = onFirstFrame, getBrightness = getBrightness, onEos = onEos,
                parkFlag = parkFlag, presentPreview = presentPreview,
            )
            process = vp; thread = vt
        }

        /** Captures this channel's live LAV packet-ring snapshot, when one exists. */
        fun snapshotCache(positionNanos: Long): ByteArray? = nativePipe?.lavCacheSnapshot(positionNanos)

        /** Seeks the in-process LAV decoder without replacing this channel. */
        fun seekInProcess(offsetNanos: Long, onFirstFrame: () -> Unit): Boolean =
            inProcess && nativePipe?.seekInProcess(offsetNanos, onFirstFrame) == true

        /** Stops the decode and joins the reader thread (blocking). Must not run on the render thread. */
        fun teardownProcess() {
            stop.set(true)
            nativePipe?.kill()
            MediaProcess.gracefulDestroy(process)
            thread?.let { joinSafely(it) }
            nativePipe?.release()
        }
    }

    private val audio = AudioSink(debugLabel)

    /**
     * True while the current session plays a live stream; set by [start], reused by every audio
     * (re)launch in the same session to pick the transport for the audio process.
     */
    @Volatile
    private var liveSession = false

    /**
     * The [HlsAudioFeeder] feeding the current audio process, or null on the direct-URL path.
     * Its first PES PTS anchors A / V pacing exactly (see [pacingClockNanos]).
     */
    @Volatile
    private var audioFeeder: HlsAudioFeeder? = null

    /**
     * Spawns the audio `FFmpeg` process for [streamSet]. Live HLS audio is fetched by an
     * [HlsAudioFeeder] on the JVM and piped into FFmpeg's stdin — the bundled binary's own TLS
     * stalls for 25-30 s on live HLS hosts before delivering any PCM (and keeps stalling
     * mid-stream), while the JVM HTTP stack reaches the same hosts instantly. Everything else
     * (VOD, progressive URLs) keeps the direct-URL invocation with FFmpeg's own `-ss` seeking.
     */
    @Throws(IOException::class)
    private fun buildAudioProcess(
        ffmpeg: String, streamSet: ActiveStreams, offsetNanos: Long, stopFlag: AtomicBoolean,
    ): Process {
        val url = streamSet.currentAudio.url
        if (liveSession && HlsAudioFeeder.supports(url)) {
            // Same SSRF gate the FFmpeg URL path applies in MediaProcess.baseCommand
            val safeUrl = MediaHostGuard.resolveSafeUrl(url)
            val proc = MediaProcess.buildAudioPiped(ffmpeg, AudioSink.SAMPLE_RATE)
            audioFeeder = HlsAudioFeeder(safeUrl, proc.outputStream, stopFlag, terminated, debugLabel)
                .also { it.start() }
            return proc
        }
        audioFeeder = null
        return MediaProcess.buildAudio(ffmpeg, url, offsetNanos, AudioSink.SAMPLE_RATE)
    }

    /**
     * The single-line reappearance bridge audio session (cached prelude -> live PCM on one line) while it
     * is still playing its prelude, before the live process is attached and it moves into [audioHalf].
     */
    @Volatile
    private var bridgeAudio: AudioHalf? = null

    /** When true, the live video + audio reader threads idle in place, keeping their decoder / line open
     *  so a returning display resumes instantly ([suspend] / [resume]). Reset false on every fresh start. */
    private val parkFlag = AtomicBoolean(false)

    init {
        audio.setParkFlag(parkFlag)
        audio.setDspStage(audioStage)
    }

    /** Guards the live/incoming channel transitions across the control, render, and reader threads. */
    private val switchLock = Any()
    @Volatile
    private var active: VideoChannel? = null
    @Volatile
    private var incoming: VideoChannel? = null
    @Volatile
    private var incomingGeneration: Long = 0L
    @Volatile
    private var audioHalf: AudioHalf? = null

    /** Fallback timestamp source when no channel is live (the watchdog guards against reading it then). */
    private val noFrames = AtomicLong(0)

    /**
     * Upper bound the shared wall clock is clamped to while a replay -> live bridge is in flight (the live
     * edge the live channel resumes at). Both the replay channel and the warming-up live channel pace
     * on this one clamped clock, so the live channel's first frame becomes due *exactly* when replay
     * reaches the edge — the handoff has no forward jump and no drop-storm. On the live channel's first
     * frame the clock is rebased to the edge and this is lifted to [Long.MAX_VALUE]. Default disables it.
     */
    @Volatile
    private var bridgeCeilingNanos: Long = Long.MAX_VALUE

    @Volatile
    var isPlaying = false; private set

    /** True once the first decoded frame of the live channel is ready for GPU upload. */
    fun textureFilled(): Boolean = active?.pipe?.textureFilled() == true

    /** Uploads the latest live frame to [texture]. Returns true if a frame was uploaded. Render thread only. */
    fun updateFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean = active?.pipe?.updateFrame(texture, w, h) == true

    /** Uploads the latest live planar I420 frame into the three plane textures. Returns true if uploaded. */
    fun updateFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        active?.pipe?.updateFramePlanar(y, u, v, w, h) == true

    /** True while an incoming (quality-switch) channel is warming up in parallel. */
    fun hasIncoming(): Boolean = incoming != null

    /** Uploads the latest incoming-channel frame to [texture] (the staged texture). Returns true if uploaded. */
    fun updateIncomingFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean =
        incoming?.pipe?.updateFrame(texture, w, h) == true

    /** Uploads the latest incoming-channel planar I420 frame into the staged plane textures. */
    fun updateIncomingFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        incoming?.pipe?.updateFramePlanar(y, u, v, w, h) == true

    /** Discards the live channel's ready frame. Call when stopping or seeking. */
    fun clearFrame() = active?.pipe?.clear() ?: Unit

    /** Frame position of the open audio line, or -1 when no line is active. */
    val audioFramePosition: Long get() = audio.framePosition

    /** Sets the effective volume (user volume * distance attenuation). */
    fun setVolume(volume: Double) {
        audio.currentVolume = volume
    }

    /** Timestamp of the live channel's last decoded video frame; read by [StreamWatchdog]. */
    val lastFrameNanos: AtomicLong get() = active?.pipe?.lastFrameReceivedNanos ?: noFrames

    @Volatile
    private var popoutSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    @Volatile
    private var previewSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    /** Routes raw frames to the popout window. Null = no popout active. */
    var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = popoutSink
        set(value) {
            popoutSink = value
            updateRawFrameSink()
        }

    /** Routes raw frames to the display menu preview when the main texture is GPU-YUV only. */
    var previewFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = previewSink
        set(value) {
            previewSink = value
            updateRawFrameSink()
        }

    private fun updateRawFrameSink() {
        val popout = popoutSink
        val preview = previewSink
        val sink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? =
            if (popout == null && preview == null) null else { buf, w, h, format ->
                val pos = buf.position()
                val limit = buf.limit()
                popout?.invoke(buf, w, h, format)
                buf.position(pos).limit(limit)
                preview?.invoke(buf, w, h, format)
                buf.position(pos).limit(limit)
            }
        // The raw sink follows the live channel; it is re-applied to the new live channel on promotion
        active?.pipe?.popoutFrameSink = sink
    }

    /**
     * Stops any running session, then launches new `FFmpeg` processes for [streamSet]
     * starting at [offsetNanos]. Wires up the clock, brightness, and EOS callbacks.
     *
     * @param lastQuality last confirmed quality in pixels; 0 = derive from stream metadata
     * @param live true for a live stream; selects the JVM-fed HLS audio transport (see [buildAudioProcess])
     */
    fun start(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int, hwAccel: HwAccelBackend, live: Boolean = false) {
        stop()
        if (terminated.get()) return
        liveSession = live
        bridgeCeilingNanos = Long.MAX_VALUE // A full start is not a bridge

        val ffmpeg = FFmpegBinary.getPath() ?: run {
            logger.error("$debugLabel FFmpeg binary not available.")
            events.onError(DreamMediaException.Decode("FFmpeg binary not available", isFatal = true)); return
        }
        clock.reset(offsetNanos)

        parkFlag.set(false)
        val (w, h) = targetDims(streamSet, lastQuality)
        val channel = VideoChannel()
        try {
            val firstVideoFrame = CountDownLatch(1)
            channel.launch(ffmpeg, streamSet, w, h, offsetNanos, hwAccel, onFirstFrame = {
                clock.markFirstFrame()
                firstVideoFrame.countDown()
            }, onEos = onStreamEnd, parkFlag = parkFlag)
            val aStop = AtomicBoolean()
            val ap = try {
                buildAudioProcess(ffmpeg, streamSet, offsetNanos, aStop)
            } catch (e: IOException) {
                // The video side is already running; tear it down before propagating
                channel.teardownProcess()
                throw e
            }
            val at = audio.start(ap, terminated, aStop, startGate = firstVideoFrame, onUnexpectedEnd = onAudioFailure)
            active = channel
            audioHalf = AudioHalf(ap, at, aStop)
            updateRawFrameSink()
            isPlaying = true
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start FFmpeg", e)
            events.onError(DreamMediaException.Decode("Failed to start FFmpeg: ${e.message}", e))
        }
    }

    /**
     * Seamless in-place seek: silences the old audio, freezes the picture on its last uploaded frame,
     * and warms the same streams at [offsetNanos] as the new live channel — all without the blocking
     * teardown-then-cold-start of a full [start]. The old session is dismantled on a background thread
     * while the new decode is already connecting, and the audio is gated on the new channel's first
     * presented frame exactly like a normal start. Returns false when the session is in a state that
     * cannot be seeked in place (bridge / quality switch in flight, parked, not playing); the caller
     * falls back to a full restart.
     */
    fun beginSeek(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int, hwAccel: HwAccelBackend): Boolean {
        if (!isPlaying || terminated.get() || parkFlag.get()) return false
        if (bridgeCeilingNanos != Long.MAX_VALUE) return false
        synchronized(switchLock) { if (incoming != null) return false }
        val old = active ?: return false
        val ffmpeg = FFmpegBinary.getPath() ?: return false
        val (w, h) = targetDims(streamSet, lastQuality)

        if (old.inProcess && old.nativePipe?.expectedW == w && old.nativePipe.expectedH == h) {
            val firstVideoFrame = CountDownLatch(1)
            val aStop = AtomicBoolean()
            val ap = try {
                buildAudioProcess(ffmpeg, streamSet, offsetNanos, aStop)
            } catch (e: IOException) {
                logger.error("$debugLabel Failed to start seek audio session.", e)
                return false
            }
            val oldAudio = audioHalf
            audioHalf = null
            oldAudio?.stop?.set(true)
            audio.stop()
            clock.reset(offsetNanos)

            val seeked = old.seekInProcess(offsetNanos) {
                clock.markFirstFrame()
                firstVideoFrame.countDown()
            }
            if (seeked) {
                val at = audio.start(ap, terminated, aStop, startGate = firstVideoFrame, onUnexpectedEnd = onAudioFailure)
                audioHalf = AudioHalf(ap, at, aStop)
                updateRawFrameSink()
                discardHalvesAsync(null, oldAudio)
                return true
            }
            logger.warn("$debugLabel In-place seek rejected by the pipe; falling back to a channel reopen.")
            MediaProcess.gracefulDestroy(ap)
            discardHalvesAsync(null, oldAudio)
        } else {
            logger.warn(
                "$debugLabel Seek can't go in place (inProcess=${old.inProcess}, " +
                        "pipe=${old.nativePipe?.expectedW}x${old.nativePipe?.expectedH}, target=${w} x $h); " +
                        "reopening the channel."
            )
        }

        // Freeze the picture and cut the sound right away: the old consumer stops presenting within one
        // poll (the GPU texture keeps the last frame on screen), and the clock parks at the target so the
        // UI reads the seeked position immediately.
        old.stop.set(true)
        val oldAudio = audioHalf
        audioHalf = null
        oldAudio?.stop?.set(true)
        audio.stop()
        clock.reset(offsetNanos)

        val channel = VideoChannel()
        try {
            val firstVideoFrame = CountDownLatch(1)
            channel.launch(ffmpeg, streamSet, w, h, offsetNanos, hwAccel, onFirstFrame = {
                clock.markFirstFrame()
                firstVideoFrame.countDown()
            }, onEos = onStreamEnd, parkFlag = parkFlag)
            val aStop = AtomicBoolean()
            val ap = try {
                buildAudioProcess(ffmpeg, streamSet, offsetNanos, aStop)
            } catch (e: IOException) {
                channel.teardownProcess()
                renderExecutor.execute { channel.pipe.cleanup() }
                throw e
            }
            val at = audio.start(ap, terminated, aStop, startGate = firstVideoFrame, onUnexpectedEnd = onAudioFailure)
            synchronized(switchLock) { active = channel }
            audioHalf = AudioHalf(ap, at, aStop)
            updateRawFrameSink()
            // The old halves are already stopping; finish dismantling them off-thread so the new decode
            // never waits on process destruction or reader joins.
            discardHalvesAsync(old, oldAudio)
            return true
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start seek session.", e)
            // Leave the old (stopping) channel as active: the caller's full restart will tear it down.
            discardHalvesAsync(null, oldAudio)
            return false
        }
    }

    /**
     * Replaces only the audio half of a playing session with a fresh process on the same audio URL,
     * leaving the video channel, the clock, and the picture untouched. Used when the live audio process
     * dies or never delivers PCM while video is decoding fine — restarting the whole session for that
     * blanks a healthy picture and, on live streams, forces a needless full re-resolve. The new line
     * starts ungated (video is already presenting); until its first PCM chunk arrives the pacing clock
     * simply stays on the wall clock, and [pacingClockNanos] re-anchors when the line comes up late.
     * Returns false when the session is in a state where only a full restart makes sense.
     */
    fun restartAudio(streamSet: ActiveStreams, offsetNanos: Long): Boolean {
        if (!isPlaying || terminated.get() || parkFlag.get()) return false
        if (bridgeCeilingNanos != Long.MAX_VALUE || bridgeAudio != null) return false
        synchronized(switchLock) { if (incoming != null) return false }
        val ffmpeg = FFmpegBinary.getPath() ?: return false
        val oldAudio = audioHalf
        audioHalf = null
        oldAudio?.stop?.set(true)
        audio.stop()
        val aStop = AtomicBoolean()
        val ap = try {
            buildAudioProcess(ffmpeg, streamSet, offsetNanos, aStop)
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start replacement audio process.", e)
            discardHalvesAsync(null, oldAudio)
            return false
        }
        wallAnchorNextAudioSession = true
        val at = audio.start(ap, terminated, aStop, startGate = null, onUnexpectedEnd = onAudioFailure)
        audioHalf = AudioHalf(ap, at, aStop)
        discardHalvesAsync(null, oldAudio)
        logger.debug("$debugLabel Audio half restarted in place at ${offsetNanos / 1_000_000} ms.")
        return true
    }

    /** Warm-up budget for a replacement audio-track process before the switch gives up and keeps the
     *  current track (better a stale language than indefinite silence on a dead URL). */
    private val audioSwitchWarmupTimeoutNanos = 15_000_000_000L

    /** Generation counter for in-flight audio-track switches: only the newest may complete its swap,
     *  so rapid re-picks and a session [stop] (which bumps it) safely orphan older warm-ups. */
    private val audioSwitchGeneration = AtomicLong()

    /**
     * Seamless audio-track switch for seekable content: spawns the new track's `FFmpeg` on a background
     * thread and lets the current line keep playing through the whole spawn + connect + seek latency —
     * the swap happens only once the replacement's first PCM is ready (the audio analogue of the
     * parallel video quality switch). At the swap, the sink discards the PCM span the new track is
     * behind the live clock ([AudioSink.CatchUp]), so the new language joins already lip-synced instead
     * of permanently lagging by the warm-up time. Returns false when the session state only supports a
     * plain restart (paused / parked / bridging / mid-quality-handoff) — callers then rely on the
     * updated stream set taking effect on the next fresh session, same as [restartAudio].
     */
    fun beginAudioTrackSwitch(streamSet: ActiveStreams): Boolean {
        if (!isPlaying || terminated.get() || parkFlag.get()) return false
        if (bridgeCeilingNanos != Long.MAX_VALUE || bridgeAudio != null) return false
        synchronized(switchLock) { if (incoming != null) return false }
        val ffmpeg = FFmpegBinary.getPath() ?: return false
        val generation = audioSwitchGeneration.incrementAndGet()
        daemon({ runAudioTrackSwitch(ffmpeg, streamSet, generation) }, "MediaPlayer-audio-switch").start()
        return true
    }

    /** True while [generation] is still the newest audio switch and the session can still take it. */
    private fun audioSwitchStillCurrent(generation: Long): Boolean =
        audioSwitchGeneration.get() == generation && !terminated.get() && isPlaying && !parkFlag.get()

    /** Background body of [beginAudioTrackSwitch]: warm up the replacement, then swap lines. */
    private fun runAudioTrackSwitch(ffmpeg: String, streamSet: ActiveStreams, generation: Long) {
        val seekNanos = clock.currentTime().coerceAtLeast(0L)
        val aStop = AtomicBoolean()
        val ap = try {
            buildAudioProcess(ffmpeg, streamSet, seekNanos, aStop)
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start replacement audio-track process.", e)
            return
        }
        // Old track keeps playing while the replacement warms up; wait for its first stdout bytes
        val deadline = System.nanoTime() + audioSwitchWarmupTimeoutNanos
        var ready = false
        while (System.nanoTime() < deadline && audioSwitchStillCurrent(generation)) {
            try {
                if (ap.inputStream.available() > 0) {
                    ready = true
                    break
                }
            } catch (_: IOException) {
                break
            }
            if (!ap.isAlive) break
            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!ready || !audioSwitchStillCurrent(generation)) {
            aStop.set(true)
            MediaProcess.gracefulDestroy(ap)
            if (!ready && audioSwitchGeneration.get() == generation) {
                logger.warn("$debugLabel Audio-track switch delivered no PCM in time; keeping the current track.")
            }
            return
        }
        // Seamless swap: the replacement line pre-buffers (silent) while the OLD one keeps playing,
        // then flips in with no audible gap (see [AudioSink.startSwitch]) — this removes the silence
        // the old stop-then-start swap left while the new line opened and filled. A catch-up skip
        // drops the span the new track fell behind the live clock so it joins already lip-synced. The
        // HLS-feeder path (live) carries its own exact PES-PTS anchor instead, so no byte skip there.
        val catchUp = if (audioFeeder == null) AudioSink.CatchUp(seekNanos) { clock.currentTime() } else null
        val oldAudio = audioHalf
        audio.startSwitch(
            ap, terminated, aStop, catchUp,
            shouldPromote = { audioSwitchStillCurrent(generation) },
            onPromoted = {
                // Runs on the switch thread the instant the new line goes live: take ownership and
                // retire the old half. wallAnchor pins the new line's clock rate to the wall clock.
                wallAnchorNextAudioSession = true
                audioHalf = AudioHalf(ap, Thread.currentThread(), aStop)
                oldAudio?.stop?.set(true)
                discardHalvesAsync(null, oldAudio)
                logger.debug("$debugLabel Audio track switched seamlessly at ${seekNanos / 1_000_000} ms.")
                // A stop() that raced the warm-up couldn't know this half yet: honor it retroactively
                if (terminated.get() || !isPlaying) {
                    aStop.set(true)
                    audio.stop()
                    MediaProcess.gracefulDestroy(ap)
                }
            },
            onAborted = {
                // The replacement never went live; keep the current track and drop the new process
                aStop.set(true)
                MediaProcess.gracefulDestroy(ap)
            },
            onUnexpectedEnd = onAudioFailure,
        )
    }

    /** Dismantles a superseded video channel and / or audio half on a background thread. */
    private fun discardHalvesAsync(video: VideoChannel?, audioHalf: AudioHalf?) {
        daemon({
            audioHalf?.let {
                MediaProcess.gracefulDestroy(it.process)
                joinSafely(it.thread)
            }
            video?.let { ch ->
                ch.teardownProcess()
                renderExecutor.execute { ch.pipe.cleanup() }
            }
        }, "MediaPlayer-session-discard").start()
    }

    /**
     * Starts cached replay video alone — no audio, no network — so a reappearing display shows frames
     * instantly. Resumes at [resumeNanos] and plays toward [liveEdgeNanos] (the saved position the live
     * source will resume at). The wall clock is clamped to [liveEdgeNanos] so replay never overruns
     * the handoff point; [attachLiveAfterReplay] takes over there. Returns false when native replay is
     * unavailable.
     */
    fun startReplayVideoOnly(
        snapshot: ByteArray,
        resumeNanos: Long,
        liveEdgeNanos: Long,
        audioPcm: ByteArray?
    ): Boolean {
        stop()
        if (terminated.get()) return false

        // Both replay and the warming-up live channel pace on this shared clock, clamped to the live
        // edge: replay plays toward it and the live channel's first frame lands exactly there.
        clock.reset(resumeNanos)
        bridgeCeilingNanos = liveEdgeNanos.coerceAtLeast(resumeNanos)

        val (w, h) = targetDims(null)
        val channel = VideoChannel()
        val pipe = channel.nativePipe ?: run { bridgeCeilingNanos = Long.MAX_VALUE; return false }
        val vt = pipe.startReplay(
            snapshot = snapshot, w = w, h = h, resumeNanos = resumeNanos, sourceFps = REPLAY_FPS,
            stopFlag = channel.stop, terminated = terminated, getAudioClock = ::pacingClockNanos,
            onFirstFrame = { clock.markFirstFrame() },
            getBrightness = getBrightness,
            onEos = { _, normalEos ->
                logger.debug("$debugLabel [reappear] replay-only video reached end (normalEos=$normalEos), holding last frame.")
            },
        ) ?: run { bridgeCeilingNanos = Long.MAX_VALUE; return false }
        channel.thread = vt
        active = channel
        // Open the single bridge line now and play the cached audio window on it; the live PCM is later
        // attached to this very line ([attachLiveAfterReplay]) so the cached -> live seam is continuous.
        if (audioPcm != null && audioPcm.size >= AudioSink.BYTES_PER_FRAME) {
            val aStop = AtomicBoolean()
            val at = audio.startBridge(audioPcm, terminated, aStop, onUnexpectedEnd = onAudioFailure)
            bridgeAudio = AudioHalf(null, at, aStop)
        }
        updateRawFrameSink()
        isPlaying = true
        logger.debug(
            "$debugLabel [reappear] replay-only video started ${w} x $h resume=${"%.1f".format(resumeNanos / 1_000_000.0)} ms " +
                    "edge=${"%.1f".format(liveEdgeNanos / 1_000_000.0)} ms audioPcm=${audioPcm?.size ?: 0}B.",
        )
        return true
    }

    /**
     * Attaches the live source while replay holds the screen. The live channel warms up as the
     * **incoming channel paced on the same clamped clock**, so its first frame becomes due exactly when
     * replay reaches the live edge — seamless, no jump. Audio is gated on that first frame (as in a
     * normal start). On the first live frame the clock is rebased to the edge (matching the audio
     * offset) and the clamp lifted, so playback continues forward with sound. The render thread
     * promotes ([promoteIncoming]). Returns false when replay is not active or live cannot start.
     */
    fun attachLiveAfterReplay(
        streamSet: ActiveStreams, liveOffsetNanos: Long, lastQuality: Int, hwAccel: HwAccelBackend,
    ): Boolean {
        if (active == null || !isPlaying || terminated.get()) return false
        val ffmpeg = FFmpegBinary.getPath() ?: return false
        liveSession = false
        val (w, h) = targetDims(streamSet, lastQuality)

        val channel = VideoChannel()
        var generation = 0L
        val previous = synchronized(switchLock) {
            generation = incomingGeneration + 1
            incomingGeneration = generation
            incoming.also { incoming = channel }
        }
        previous?.let { discardChannelAsync(it) }
        if (terminated.get()) {
            synchronized(switchLock) { if (incoming === channel && incomingGeneration == generation) incoming = null }
            discardChannelBlocking(channel)
            return false
        }

        return try {
            val firstLiveFrame = CountDownLatch(1)
            channel.launch(
                ffmpeg,
                streamSet,
                w,
                h,
                liveOffsetNanos,
                hwAccel,
                onFirstFrame = {
                    // Live reached the edge: re-anchor the clock there (matching the audio offset), lift the
                    // bridge clamp, then open the live audio gate. The cached bridge audio is not stopped here
                    // — it streams the live PCM straight on, on its own line, so the audio seam is continuous.
                    clock.rebaseTo(liveOffsetNanos)
                    audio.onBridgeHandoff() // Let the bridge line's (live-relative) clock drive pacing now
                    bridgeCeilingNanos = Long.MAX_VALUE
                    firstLiveFrame.countDown()
                    logger.debug(
                        "$debugLabel [reappear] live channel presented first frame; handoff at ${
                            "%.1f".format(
                                liveOffsetNanos / 1_000_000.0
                            )
                        } ms."
                    )
                },
                onEos = { stderr, normalEos ->
                    abortIncoming(
                        generation,
                        "eos=$normalEos stderr=${MediaUtil.truncate(stderr)}."
                    )
                },
                parkFlag = parkFlag
            )

            val ap = MediaProcess.buildAudio(ffmpeg, streamSet.currentAudio.url, liveOffsetNanos, AudioSink.SAMPLE_RATE)
            val bridge = bridgeAudio
            if (bridge != null) {
                // Cached prelude is playing on the bridge line: feed the live PCM to that same line, so it
                // continues sample-continuously off the prelude (no gate, no flush, no second line).
                audio.provideLiveInput(ap, bridge.stop)
                audioHalf = AudioHalf(ap, bridge.thread, bridge.stop)
                bridgeAudio = null
            } else {
                // No cached prelude: live audio joins at the edge, gated on the first live frame (as in start()).
                val aStop = AtomicBoolean()
                val at = audio.start(ap, terminated, aStop, startGate = firstLiveFrame, onUnexpectedEnd = onAudioFailure)
                audioHalf = AudioHalf(ap, at, aStop)
            }

            if (terminated.get()) {
                synchronized(switchLock) {
                    if (incoming === channel && incomingGeneration == generation) incoming = null
                }
                discardChannelBlocking(channel)
                return false
            }
            logger.debug("$debugLabel [reappear] live attached ${w} x $h at ${"%.1f".format(liveOffsetNanos / 1_000_000.0)} ms, warming up...")
            true
        } catch (e: IOException) {
            logger.error("$debugLabel [reappear] failed to attach live after replay.", e)
            val wasCurrent = synchronized(switchLock) {
                if (incoming === channel && incomingGeneration == generation) {
                    incoming = null; true
                } else false
            }
            discardChannelBlocking(channel)
            if (wasCurrent) onQualitySwitchAborted(false)
            false
        }
    }

    /**
     * Captures the live channel's entire encoded-packet cache (the whole rolling window), so a later
     * replay has a real buffer to play while the live source re-resolves. Null when no cache. While a
     * replay -> live bridge is still in flight the live channel is the incoming one (the [active]
     * channel is the cache-less replay player), so capture from it — otherwise a quick leave-and-return
     * during the bridge would snapshot nothing and lose the cache.
     */
    fun captureVideoCacheSnapshot(): ByteArray? {
        val bridging = bridgeCeilingNanos != Long.MAX_VALUE
        val channel = if (bridging) (incoming ?: active) else active
        return channel?.snapshotCache(Long.MIN_VALUE)
    }

    /** The live edge a replay -> live bridge is currently resuming toward, or null when no bridge is in flight. */
    fun activeBridgeEdgeNanos(): Long? = bridgeCeilingNanos.takeIf { it != Long.MAX_VALUE }

    @Volatile
    private var parkStartNanos = 0L
    @Volatile
    private var frozenPositionNanos = -1L

    /**
     * Whether this session can be parked warm for out-of-render-distance dormancy: steady
     * in-process-libav playback only, since a dormant pool member should not keep an external
     * `FFmpeg` process and its connection tied up for an unbounded time.
     */
    fun canPark(): Boolean = canHoldWarm() && active?.inProcess == true

    /**
     * Whether the session can hold its position warm at all: something is playing, and no replay
     * bridge or quality switch is in flight. Unlike [canPark] this includes the external-process
     * pipelines — a full pipe back-pressures `FFmpeg` into a standstill, which is exactly what a
     * user-initiated pause wants (instant resume, no cold restart).
     */
    private fun canHoldWarm(): Boolean =
        isPlaying && !terminated.get() && active != null &&
                bridgeCeilingNanos == Long.MAX_VALUE && incoming == null && audioHalf != null

    /**
     * Parks the live session: the video + audio reader threads idle in place (decoder + audio line stay
     * open, position frozen), so [resume] continues instantly without re-resolving or cold-decoding.
     * [allowExternalProcess] extends this to the `FFmpeg`-process pipelines (used for user pause; see
     * [canPark] for why dormancy parking stays in-process only).
     * Returns false when the session is not in a parkable state (caller should tear down instead).
     */
    fun suspend(allowExternalProcess: Boolean = false): Boolean {
        if (!(if (allowExternalProcess) canHoldWarm() else canPark()) || parkFlag.get()) return false
        parkFlag.set(true)
        audio.pauseForPark()
        active?.pipe?.trimForPark()
        val fp = audio.framePosition
        frozenPositionNanos = if (fp >= 0) clock.audioClockNanos(fp, AudioSink.SAMPLE_RATE) else clock.currentTime()
        parkStartNanos = System.nanoTime()
        logger.debug("$debugLabel [park] session parked warm at ${"%.1f".format(frozenPositionNanos / 1_000_000.0)}ms.")
        return true
    }

    /** Un-parks a [suspend]ed session: the readers resume from the frozen position; the wall clock is
     *  shifted past the dormant interval so the position continues instead of jumping ahead. */
    fun resume() {
        if (!parkFlag.get()) return
        clock.addPausedDuration(System.nanoTime() - parkStartNanos)
        frozenPositionNanos = -1L
        parkFlag.set(false)
        audio.resumeFromPark()
        logger.debug("$debugLabel [park] session un-parked; resuming from frozen position.")
    }

    /** True while the session is parked warm. */
    fun isParked(): Boolean = parkFlag.get()

    /** The frozen playback position while parked (so an evicted park saves where the viewer left, not a
     *  position drifted forward by the dormant wall-clock time), or null when not parked. */
    fun parkedPositionNanos(): Long? = frozenPositionNanos.takeIf { parkFlag.get() && it >= 0 }

    /** Captures up to [maxNanos] of recently played PCM for the reappearance audio bridge, or null. */
    fun captureAudioPcm(maxNanos: Long): ByteArray? {
        val maxBytes = (maxNanos / 1_000_000_000.0 * AudioSink.SAMPLE_RATE * AudioSink.BYTES_PER_FRAME).toInt()
        return audio.snapshotPcm(maxBytes).takeIf { it.isNotEmpty() }
    }

    /**
     * When the audio line's clock first becomes available more than this far behind the wall clock,
     * the audio started late (live HLS audio can take many seconds to deliver its first PCM while video
     * already plays on the wall clock). Pacing then anchors the audio clock forward to the wall position
     * instead of letting the whole video timeline snap back — which would make every queued frame read
     * as seconds "ahead" and be dropped as a stale timeline. Below the threshold, no bias is applied and
     * pacing follows the audio clock absolutely, so small start offsets self-correct through video
     * holds / drops into true content sync.
     */
    private val lateAudioToleranceNanos = 500_000_000L

    /**
     * One-shot: the next fresh audio session must anchor its pacing clock to the wall clock exactly,
     * whatever the delta. Set for in-place audio restarts (track switch, live audio recovery), whose
     * line restarts at frame 0 while [PlaybackClock.seekOffsetNanos] still holds the original session's
     * seek — the absolute value of `audioClockNanos` is meaningless there and must not be trusted the
     * way a fresh full session's is. Content-level sync is handled separately ([AudioSink.CatchUp]).
     */
    @Volatile
    private var wallAnchorNextAudioSession = false

    /**
     * How far behind the current wall position an exact PTS anchor may pull the pacing clock: a
     * hold this long clears through normal pacing waits, anything more would trip the stale-timeline
     * drop (`FramePacing`, 1 s) on every queued frame.
     */
    private val maxBackwardAnchorNanos = 800_000_000L

    /** Pacing offset added to the audio clock of a late-started audio session (see [pacingClockNanos]). */
    @Volatile
    private var audioAnchorBiasNanos = 0L

    /** [AudioSink.sessionEpoch] the current [audioAnchorBiasNanos] was computed for; -1 = none yet. */
    @Volatile
    private var audioAnchorEpoch = -1

    /** Audio-clock pacing position in nanos, or -1 when neither the audio line nor the wall clock is up yet. */
    private fun pacingClockNanos(): Long {
        val fp = audio.framePosition
        if (fp >= 0) {
            val audioNanos = clock.audioClockNanos(fp, AudioSink.SAMPLE_RATE)
            val epoch = audio.sessionEpoch
            if (audioAnchorEpoch != epoch) {
                anchorAudioClock(audioNanos)
                audioAnchorEpoch = epoch
            }
            return audioNanos + audioAnchorBiasNanos
        }
        if (!clock.isRunning) return -1L
        // While a replay -> live bridge is active the wall clock is clamped to the live edge so it never
        // overruns the handoff point (otherwise the live channel's first frame arrives "late" and is
        // dropped instead of presented, and the audio gate never opens).
        return clock.currentTime().coerceAtMost(bridgeCeilingNanos)
    }

    /**
     * Computes [audioAnchorBiasNanos] on the first clock sample of a fresh audio session.
     *
     * Preferred source is the exact content offset between the independently joined HLS streams:
     * the audio feeder's first PES PTS minus the video channel's first raw LAV PTS, both on the
     * segmenter's shared 90 kHz clock. That pins video frame X to the audio sample carrying the
     * same stream time — real lip sync, not an approximation ([exactAvBiasNanos]).
     *
     * Without it (direct-URL audio, no LAV PTS) the fallback depends on the session kind:
     * - A restarted line ([wallAnchorNextAudioSession]) anchors to the wall clock exactly, in either
     *   direction — its `audioClockNanos` absolute value is built on a stale seek offset, so only the
     *   line's *rate* is meaningful; content alignment is handled separately ([AudioSink.CatchUp]).
     * - A fresh full session applies a bias only for a pathologically late start (past
     *   [lateAudioToleranceNanos]); small deltas keep bias 0 so pacing follows the audio clock
     *   absolutely and start offsets self-correct into true content sync via video holds / drops.
     */
    private fun anchorAudioClock(audioNanos: Long) {
        val wallNow = if (clock.isRunning) clock.currentTime().coerceAtMost(bridgeCeilingNanos) else 0L
        val wallAnchor = wallAnchorNextAudioSession
        wallAnchorNextAudioSession = false
        val exact = exactAvBiasNanos()
        if (exact != null) {
            val floor = wallNow - audioNanos - maxBackwardAnchorNanos
            audioAnchorBiasNanos = maxOf(exact, floor)
            logger.debug(
                "$debugLabel A/V anchored by stream PTS: audio joined ${exact / 1_000_000} ms " +
                        "${if (exact >= 0) "ahead of" else "behind"} the video join" +
                        if (audioAnchorBiasNanos != exact) {
                            " (floored by ${(audioAnchorBiasNanos - exact) / 1_000_000} ms: live video can't rewind)."
                        } else "."
            )
            return
        }
        val delta = wallNow - audioNanos
        if (wallAnchor) {
            audioAnchorBiasNanos = delta
            logger.debug(
                "$debugLabel Restarted audio line wall-anchored (raw clock ${abs(delta) / 1_000_000} ms " +
                        "${if (delta >= 0) "behind" else "ahead of"} the wall)."
            )
            return
        }
        audioAnchorBiasNanos = if (delta > lateAudioToleranceNanos) delta else 0L
        if (audioAnchorBiasNanos != 0L) {
            logger.warn(
                "$debugLabel Audio clock started ${delta / 1_000_000} ms behind the wall clock " +
                        "(late audio start); anchoring pacing forward."
            )
        }
    }

    /**
     * Exact audio-vs-video content offset from shared stream PTS (see [anchorAudioClock]), or null
     * when either side hasn't observed its first PTS or the difference is implausible (different
     * clocks, a PTS wrap, or a mid-session discontinuity): more than a live window apart, or audio
     * behind video by more than pacing could absorb without dropping frames as a stale timeline.
     */
    private fun exactAvBiasNanos(): Long? {
        val a0 = audioFeeder?.firstPtsNanos ?: return null
        if (a0 < 0) return null
        val r0 = active?.nativePipe?.firstRawPtsNanos ?: return null
        if (r0 == Long.MIN_VALUE) return null
        val bias = a0 - r0
        return bias.takeIf { it > -800_000_000L && it < 30_000_000_000L }
    }

    /** Resolves the decode dimensions: the current/target texture size when known, else from quality. */
    private fun targetDims(streamSet: ActiveStreams?, lastQuality: Int = 0): Pair<Int, Int> {
        val (tw, th) = getTextureSize()
        if (tw > 0 && th > 0) return tw to th
        val q = when {
            lastQuality > 0 -> lastQuality
            streamSet != null -> MediaStreamSelector.parseQuality(streamSet.currentVideo)
            else -> 0
        }
        if (q <= 0) return 854 to 480
        return MediaStreamSelector.qualityToDims(q).let { it[0] to it[1] }
    }

    /**
     * Seamless quality switch: launches [streamSet]'s new-quality video as a parallel incoming
     * channel while the live channel keeps decoding and rendering the old resolution. Audio and the
     * clock are untouched. The render thread promotes the incoming channel on its first frame
     * ([promoteIncoming]); on incoming failure the handoff is aborted and the live channel stays.
     * Falls back to a full [start] when nothing is playing. Must be called from the control thread.
     */
    fun beginQualitySwitch(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int, hwAccel: HwAccelBackend) {
        if (active == null || !isPlaying || terminated.get()) {
            // Nothing to hand off from: drop the staged texture, but the target quality still takes
            // effect below via a full start on the same (new) stream set — not a real failure.
            onQualitySwitchAborted(true)
            start(streamSet, offsetNanos, lastQuality, hwAccel)
            return
        }
        val ffmpeg = FFmpegBinary.getPath() ?: run { onQualitySwitchAborted(false); return }

        // Supersede any in-flight switch (rapid quality changes)
        val channel = VideoChannel()
        var generation = 0L
        val previous = synchronized(switchLock) {
            generation = incomingGeneration + 1
            incomingGeneration = generation
            incoming.also { incoming = channel }
        }
        previous?.let {
            if (MediaPlayer.DEBUG) logger.debug("$debugLabel Superseding incoming video handoff #${generation - 1}.")
            discardChannelAsync(it)
        }
        if (terminated.get()) {
            synchronized(switchLock) {
                if (incoming === channel && incomingGeneration == generation) incoming = null
            }
            discardChannelBlocking(channel)
            return
        }

        val (w, h) = targetDims(streamSet, lastQuality)
        try {
            // No latch / audio gate: the clock is already running. EOS aborts only this handoff
            if (MediaPlayer.DEBUG) {
                logger.debug(
                    "$debugLabel Starting incoming video handoff #$generation ${w} x $h " +
                            "at ${"%.1f".format(offsetNanos / 1_000_000.0)} ms.",
                )
            }
            channel.launch(
                ffmpeg,
                streamSet,
                w,
                h,
                offsetNanos,
                hwAccel,
                onFirstFrame = {
                    clock.markFirstFrame()
                    if (MediaPlayer.DEBUG) logger.debug("$debugLabel Incoming video handoff #$generation presented its first frame.")
                },
                onEos = { stderr, normalEos ->
                    abortIncoming(
                        generation,
                        "eos=$normalEos stderr=${MediaUtil.truncate(stderr)}."
                    )
                },
                parkFlag = parkFlag,
                // No pre-prime preview: the incoming channel's first decoded frame is stale by the
                // session-open time, and presenting it would promote a rewound picture that then holds
                // until decode catches the clock. Promote on the first *paced* frame instead.
                presentPreview = false,
            )
            val shouldDiscard = synchronized(switchLock) {
                if (!terminated.get() && active != null && incoming === channel && incomingGeneration == generation) {
                    false
                } else if (incoming === channel && incomingGeneration == generation) {
                    incoming = null
                    true
                } else {
                    false
                }
            }
            if (shouldDiscard) discardChannelBlocking(channel)
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start incoming video for quality switch.", e)
            val wasCurrent = synchronized(switchLock) {
                if (incoming === channel && incomingGeneration == generation) {
                    incoming = null
                    true
                } else {
                    false
                }
            }
            discardChannelBlocking(channel)
            if (wasCurrent) onQualitySwitchAborted(false)
        }
    }

    /**
     * Promotes the incoming quality-switch channel to live: the new channel becomes the rendered one
     * and the old channel is torn down off-thread. Called from the render thread the moment the
     * incoming channel's first frame has been uploaded to the staged texture, so the swap is seamless.
     */
    fun promoteIncoming(): Boolean {
        val old: VideoChannel?
        val generation: Long
        synchronized(switchLock) {
            val inc = incoming ?: return false
            generation = incomingGeneration
            incoming = null
            old = active
            active = inc
        }
        if (MediaPlayer.DEBUG) logger.debug("$debugLabel Promoted incoming video handoff #$generation.")
        updateRawFrameSink() // Re-attach popout / preview to the new live channel
        old?.let { discardChannelAsync(it) }
        return true
    }

    /** Aborts an in-flight quality switch (incoming EOS / failure): drops the incoming channel, keeps the live one. */
    private fun abortIncoming(generation: Long, reason: String) {
        val inc = synchronized(switchLock) {
            if (incomingGeneration != generation) null else incoming.also { incoming = null }
        } ?: return
        if (MediaPlayer.DEBUG) logger.debug("$debugLabel Aborted incoming video handoff #$generation ($reason).")
        discardChannelAsync(inc)
        onQualitySwitchAborted(false)
    }

    /** Tears down [channel] (process join) on a background thread, then releases its GL resources on the render thread. */
    private fun discardChannelAsync(channel: VideoChannel) {
        daemon({
            channel.teardownProcess()
            renderExecutor.execute { channel.pipe.cleanup() }
        }, "MediaPlayer-video-discard").start()
    }

    /** Tears down [channel] inline (caller must not be the render thread), then frees its GL resources. */
    private fun discardChannelBlocking(channel: VideoChannel) {
        channel.teardownProcess()
        renderExecutor.execute { channel.pipe.cleanup() }
    }

    /**
     * Signals all stop flags, destroys the `FFmpeg` processes, closes the audio line, and joins the
     * reader threads. Tears down any in-flight quality switch too. Safe to call when idle.
     */
    fun stop() {
        isPlaying = false
        bridgeCeilingNanos = Long.MAX_VALUE
        audioFeeder = null
        parkFlag.set(false) // Release any parked readers so they observe the stop flags and exit
        // A reappearance bridge whose live process never attached: flag it; audio.stop() below releases the
        // line and the pending live-input gate, and the thread is joined at the end.
        bridgeAudio?.let { it.stop.set(true) }
        val inc = synchronized(switchLock) {
            incomingGeneration += 1
            incoming.also { incoming = null }
        }
        inc?.let { discardChannelBlocking(it) }

        val a = active
        active = null
        audioHalf?.let { it.stop.set(true) }
        a?.let { it.stop.set(true); it.nativePipe?.kill() }
        audioHalf?.let { MediaProcess.gracefulDestroy(it.process) }
        audio.stop()
        a?.let {
            MediaProcess.gracefulDestroy(it.process)
            it.thread?.let { t -> joinSafely(t) }
            it.nativePipe?.release()
            renderExecutor.execute { it.pipe.cleanup() }
        }
        audioHalf?.thread?.let { joinSafely(it) }
        audioHalf = null
        // Join a still-pending bridge thread (live never attached, so it never moved into audioHalf)
        bridgeAudio?.thread?.let { joinSafely(it) }
        bridgeAudio = null
    }

    /**
     * Releases any remaining pipe GL resources. Called once when this session manager is permanently
     * discarded (the owning `MediaPlayer` is stopping for good). [stop] normally clears channels first.
     */
    fun cleanup() {
        synchronized(switchLock) { incoming.also { incoming = null } }?.let { discardChannelBlocking(it) }
        active?.let { ch ->
            active = null
            ch.nativePipe?.release()
            renderExecutor.execute { ch.pipe.cleanup() }
        }
    }
}
