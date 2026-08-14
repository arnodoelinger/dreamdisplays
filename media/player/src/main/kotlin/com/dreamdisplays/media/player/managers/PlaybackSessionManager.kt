package com.dreamdisplays.media.player.managers

import com.dreamdisplays.api.media.common.DreamMediaException
import com.dreamdisplays.api.media.common.FramePixelFormat
import com.dreamdisplays.api.media.audio.AudioDspStage
import com.dreamdisplays.api.media.player.FrameUploaderFactory
import com.dreamdisplays.api.media.player.GpuTextureRef
import com.dreamdisplays.api.media.player.RenderThreadExecutor
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.events.PlayerEvents
import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.dreamdisplays.media.player.pipeline.*
import com.dreamdisplays.media.player.process.FFmpegBinary
import com.dreamdisplays.media.player.process.HwAccelBackend
import com.dreamdisplays.media.player.process.MediaProcess
import com.dreamdisplays.media.player.stream.ActiveStreams
import com.dreamdisplays.media.player.stream.MediaStreamSelector
import com.dreamdisplays.media.player.util.MediaUtil
import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.media.player.util.joinSafely
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages a playback session: video decode channels plus the native audio session. Quality switch runs
 * a parallel video channel.
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

    /** Invoked when quality switch fails before promotion (can drop staged texture) */
    private val onQualitySwitchAborted: (appliedAnyway: Boolean) -> Unit = {},

    /** Invoked when the native audio session ends unexpectedly (called on the watcher thread). */
    private val onAudioFailure: (stderr: String) -> Unit = {},

    /** Invoked once an in-flight [beginAudioTrackSwitch] settles, either way (promoted or gave up). */
    private val onAudioTrackSwitchSettled: () -> Unit = {},

    /** Runs render-thread (GL) cleanup work. */
    private val renderExecutor: RenderThreadExecutor,

    /** Creates per-channel GPU frame uploaders. */
    private val uploaderFactory: FrameUploaderFactory,

    /** Whether the GPU-side planar (I420) render path is active. */
    private val gpuYuvActive: Boolean,

    /** Optional per-display acoustics DSP stage; null keeps the legacy distance-gain-only pipeline. */
    audioStage: AudioDspStage? = null,
) {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/PlaybackSession")

    private companion object {
        /** Pacing cadence for replay-only video; PTS still drives pacing, this is only the fallback. */
        const val REPLAY_FPS = 30.0

        /** How many silent-source verdicts to remember; well past any one player's stream ladder. */
        const val SILENT_MEMO_LIMIT = 64

        /** Audio URLs proven to carry no audio track (process-wide, insertion-bounded). */
        val SILENT_SOURCES: MutableSet<String> = Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean =
                    size > SILENT_MEMO_LIMIT
            }.let { Collections.synchronizedMap(it) },
        )

        /** True when [url] looks like an HLS playlist (Twitch live weaver URLs carry no `.m3u8` suffix). */
        fun isLiveHlsUrl(url: String): Boolean = url.contains(".m3u8") || url.contains(".ttvnw.net/")
    }

    /**
     * One decode channel: video pipe + process/thread/stop (independent instances per channel).
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

        /**
         * True when decoding via in-process libav (only path that supports warm park).
         */
        @Volatile
        var inProcess = false
            private set
        val stop = AtomicBoolean()

        /**
         * Launches video decode into channel's pipe (in-process libav, native, or JVM FFmpeg).
         */
        fun launch(
            ffmpeg: String, streamSet: ActiveStreams, w: Int, h: Int, offsetNanos: Long,
            hwAccel: HwAccelBackend, onFirstFrame: () -> Unit, onEos: (String, Boolean) -> Unit,
            getAudioClock: () -> Long = ::pacingClockNanos, parkFlag: AtomicBoolean? = null,
            presentPreview: Boolean = true,
        ) {
            // SSRF guard for the in-process libav path, which bypasses MediaProcess.baseCommand
            val safeUrl = MediaHostGuard.resolveSafeUrl(streamSet.currentVideo.url)
            // One sanitized rate for both FFmpeg's -r and the pipe's timestamp arithmetic, so the
            // two can never disagree (see MediaProcess.outputFps).
            val fps = MediaProcess.outputFps(streamSet.currentVideo.fps)
            // The in-process decoder seeks through the same libav demuxer that gets this container
            // wrong, and unlike the process path it has no way to fall back to decoding forward.
            val seekByDecoding = streamSet.currentVideo.seekByDecoding
            val lavThread = if (nativePipe != null && NativeMedia.lavInProcessEnabled && !seekByDecoding) {
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
                val args = MediaProcess.videoArgs(
                    ffmpeg, safeUrl, w, h, offsetNanos, hwAccel, transport, fps,
                    alreadyResolved = true, seekByDecoding = seekByDecoding,
                )
                val vt = nativePipe.start(
                    args = args, w = w, h = h, nv12 = nv12, seekOffsetNanos = offsetNanos, sourceFps = fps,
                    stopFlag = stop, terminated = terminated, getAudioClock = getAudioClock,
                    onFirstFrame = onFirstFrame, getBrightness = getBrightness, onEos = onEos,
                    parkFlag = parkFlag, presentPreview = presentPreview,
                ) ?: throw IOException("Native FFmpeg session failed to start")
                process = null; thread = vt; return
            }
            val vp = MediaProcess.buildVideo(
                ffmpeg, safeUrl, w, h, offsetNanos, hwAccel, fps,
                alreadyResolved = true, seekByDecoding = seekByDecoding,
            )
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

    private val audio = NativeAudioSink(debugLabel, terminated, audioStage)

    /**
     * True while the current session plays a live stream; set by [start], reused by every audio
     * (re)launch in the same session to pick the transport for the audio session.
     */
    @Volatile
    private var liveSession = false

    /** Resolves [streamSet]'s audio URL/HLS-routing, or null when this source is already known silent. */
    private fun resolveAudio(streamSet: ActiveStreams): Pair<String, Boolean>? {
        val url = streamSet.currentAudio.url
        if (url in SILENT_SOURCES) {
            silentSession = true
            return null
        }
        val isHls = streamSet.currentAudio.seekByDecoding || (liveSession && isLiveHlsUrl(url))
        return url to isHls
    }

    /** True once source has no audio (separate from transient gap between sessions). */
    @Volatile
    private var silentSession = false

    /** Marks URL as silent source (no session opened, returns true on first mark). */
    fun markSourceSilent(audioUrl: String): Boolean {
        silentSession = true
        return SILENT_SOURCES.add(audioUrl)
    }

    /** When true, threads idle in place keeping decoder open for instant resume. */
    private val parkFlag = AtomicBoolean(false)

    /** Pre-warmed shadow sessions for the audio tracks that are not playing; see [AudioTrackWarmPool]. */
    private val audioWarmPool = AudioTrackWarmPool(
        debugLabel = debugLabel,
        terminated = terminated,
        positionNanos = { clock.currentTime() },
        eligible = { isPlaying && !terminated.get() && !parkFlag.get() && !liveSession },
    )

    /** Declares the audio tracks to keep pre-warmed; the one currently playing must not be among them. */
    fun setWarmAudioTracks(tracks: List<WarmTrack>) =
        audioWarmPool.setTracks(tracks.filter { it.url !in SILENT_SOURCES })

    /** Guards the live/incoming channel transitions across the control, render, and reader threads. */
    private val switchLock = Any()

    @Volatile
    private var active: VideoChannel? = null

    @Volatile
    private var incoming: VideoChannel? = null

    @Volatile
    private var incomingGeneration: Long = 0L

    /** Fallback timestamp source when no channel is live (the watchdog guards against reading it then). */
    private val noFrames = AtomicLong(0)

    /** Upper bound the shared wall clock is clamped to while a replay -> live bridge is in flight (the live edge the replay is catching up to). */
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

    /** Stops any running session, then launches new decode channels for [streamSet] starting at [offsetNanos]. */
    fun start(
        streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int, hwAccel: HwAccelBackend, live: Boolean = false,
        onFirstFrame: () -> Unit = {},
    ) {
        stop()
        if (terminated.get()) return
        liveSession = live
        silentSession = false
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
                onFirstFrame()
            }, onEos = onStreamEnd, parkFlag = parkFlag)
            active = channel
            resolveAudio(streamSet)?.let { (url, isHls) ->
                audio.start(
                    url, isHls, contentStartNanos = offsetNanos,
                    startGate = firstVideoFrame, onUnexpectedEnd = onAudioFailure,
                )
            }
            updateRawFrameSink()
            isPlaying = true
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start FFmpeg", e)
            events.onError(DreamMediaException.Decode("Failed to start FFmpeg: ${e.message}", e))
        }
    }

    /** Seamless in-place seek: silences old audio, freezes picture on last frame, warms new stream at offset. */
    fun beginSeek(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int, hwAccel: HwAccelBackend): Boolean {
        if (!isPlaying || terminated.get() || parkFlag.get()) return false
        // The shadows hold PCM for the position we are leaving; the refresh pass re-warms at the new one.
        audioWarmPool.invalidateAll()
        if (bridgeCeilingNanos != Long.MAX_VALUE) return false
        synchronized(switchLock) { if (incoming != null) return false }
        val old = active ?: return false
        val ffmpeg = FFmpegBinary.getPath() ?: return false
        val (w, h) = targetDims(streamSet, lastQuality)

        if (old.inProcess && old.nativePipe?.expectedW == w && old.nativePipe.expectedH == h) {
            val firstVideoFrame = CountDownLatch(1)
            audio.stop()
            clock.reset(offsetNanos)

            val seeked = old.seekInProcess(offsetNanos) {
                clock.markFirstFrame()
                firstVideoFrame.countDown()
            }
            if (seeked) {
                resolveAudio(streamSet)?.let { (url, isHls) ->
                    audio.start(
                        url, isHls, contentStartNanos = offsetNanos,
                        startGate = firstVideoFrame, onUnexpectedEnd = onAudioFailure,
                    )
                }
                updateRawFrameSink()
                return true
            }
            logger.warn("$debugLabel In-place seek rejected by the pipe; falling back to a channel reopen.")
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
        audio.stop()
        clock.reset(offsetNanos)

        val channel = VideoChannel()
        try {
            val firstVideoFrame = CountDownLatch(1)
            channel.launch(ffmpeg, streamSet, w, h, offsetNanos, hwAccel, onFirstFrame = {
                clock.markFirstFrame()
                firstVideoFrame.countDown()
            }, onEos = onStreamEnd, parkFlag = parkFlag)
            synchronized(switchLock) { active = channel }
            resolveAudio(streamSet)?.let { (url, isHls) ->
                audio.start(
                    url, isHls, contentStartNanos = offsetNanos,
                    startGate = firstVideoFrame, onUnexpectedEnd = onAudioFailure,
                )
            }
            updateRawFrameSink()
            // The old channel is already stopping; finish dismantling it off-thread so the new decode
            // never waits on process destruction or reader joins.
            discardChannelAsync(old)
            return true
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start seek session.", e)
            // Leave the old (stopping) channel as active: the caller's full restart will tear it down.
            return false
        }
    }

    /** Replaces the audio session with a fresh one on the same audio URL, leaving video unchanged. */
    fun restartAudio(streamSet: ActiveStreams, offsetNanos: Long): Boolean {
        if (!isPlaying || terminated.get() || parkFlag.get()) return false
        if (bridgeCeilingNanos != Long.MAX_VALUE) return false
        synchronized(switchLock) { if (incoming != null) return false }
        audio.stop()
        val target = resolveAudio(streamSet)
        if (target == null) {
            // The source has no audio track: there is nothing to restart, and the session is healthy
            logger.debug("$debugLabel Audio restart skipped: this source plays silently.")
            return true
        }
        val (url, isHls) = target
        audio.start(url, isHls, contentStartNanos = offsetNanos, onUnexpectedEnd = onAudioFailure)
        logger.debug("$debugLabel Audio restarted in place at ${offsetNanos / 1_000_000} ms.")
        return true
    }

    /** Generation counter for in-flight audio-track switches: only the newest may complete its swap,
     *  so rapid re-picks and a session [stop] (which bumps it) safely orphan older warm-ups. */
    private val audioSwitchGeneration = AtomicLong()

    /** Seamless audio-track switch for seekable content: warms up the replacement natively, then swaps. */
    fun beginAudioTrackSwitch(streamSet: ActiveStreams): Boolean {
        if (!isPlaying || terminated.get() || parkFlag.get()) return false
        if (bridgeCeilingNanos != Long.MAX_VALUE) return false
        synchronized(switchLock) { if (incoming != null) return false }
        val generation = audioSwitchGeneration.incrementAndGet()
        daemon({ runAudioTrackSwitch(streamSet, generation) }, "MediaPlayer-audio-switch").start()
        return true
    }

    /** True while [generation] is still the newest audio switch and the session can still take it. */
    private fun audioSwitchStillCurrent(generation: Long): Boolean =
        audioSwitchGeneration.get() == generation && !terminated.get() && isPlaying && !parkFlag.get()

    /**
     * Claims the pre-warmed session for the target track, or null when none is pooled. Warm shadows are
     * only kept for non-live tracks (see [audioWarmPool]'s `eligible`), matching the guard here.
     */
    private fun takeWarmAudioLine(streamSet: ActiveStreams): AudioTrackWarmPool.Warm? {
        if (liveSession) return null
        val w = audioWarmPool.take(streamSet.currentAudio.url) ?: return null
        logger.debug(
            "$debugLabel Audio-track switch served from the warm pool " +
                    "(spawned at ${w.contentStartNanos / 1_000_000} ms)."
        )
        return w
    }

    /** Background body of [beginAudioTrackSwitch]: adopt a warm session, or open + prime one, then swap. */
    private fun runAudioTrackSwitch(streamSet: ActiveStreams, generation: Long) {
        val warm = takeWarmAudioLine(streamSet)
        if (warm != null) {
            if (!audioSwitchStillCurrent(generation)) {
                NativeMedia.lavAudioKill(warm.handle)
                NativeMedia.lavAudioClose(warm.handle)
                onAudioTrackSwitchSettled()
                return
            }
            audio.adopt(warm.handle, warm.isHls, warm.contentStartNanos, onUnexpectedEnd = onAudioFailure)
            logger.debug("$debugLabel Audio track switched seamlessly from the warm pool at ${warm.contentStartNanos / 1_000_000} ms.")
            onAudioTrackSwitchSettled()
            return
        }
        if (!audioSwitchStillCurrent(generation)) {
            onAudioTrackSwitchSettled()
            return
        }
        val target = resolveAudio(streamSet)
        if (target == null) {
            onAudioTrackSwitchSettled()
            return
        }
        val (url, isHls) = target
        val seekNanos = clock.currentTime().coerceAtLeast(0L)
        // Seamless swap: the replacement session pre-buffers (paused) while the OLD one keeps playing,
        // then flips in with no audible gap (see NativeAudioSink.startSwitch).
        audio.startSwitch(
            url, isHls, seekNanos,
            shouldPromote = { audioSwitchStillCurrent(generation) },
            onPromoted = {
                logger.debug("$debugLabel Audio track switched seamlessly at ${seekNanos / 1_000_000} ms.")
                onAudioTrackSwitchSettled()
            },
            onAborted = { onAudioTrackSwitchSettled() },
            onUnexpectedEnd = onAudioFailure,
        )
    }

    /** Starts cached replay video alone (no audio, no network) so reappearing display shows frames instantly. */
    fun startReplayVideoOnly(
        snapshot: ByteArray,
        resumeNanos: Long,
        liveEdgeNanos: Long,
        audioPcm: ByteArray?,
        audioSampleRate: Int,
        audioChannels: Int,
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
        // Open the single bridge session now and play the cached audio window on it; the live PCM is
        // later attached to this very session ([attachLiveAfterReplay]) so the cached -> live seam is
        // continuous.
        if (audioPcm != null && audioPcm.isNotEmpty() && audioChannels > 0 && audioSampleRate > 0) {
            audio.startBridge(audioPcm, audioSampleRate, audioChannels, liveEdgeNanos, onUnexpectedEnd = onAudioFailure)
        }
        updateRawFrameSink()
        isPlaying = true
        logger.debug(
            "$debugLabel [reappear] replay-only video started $w x $h resume=${"%.1f".format(resumeNanos / 1_000_000.0)} ms " +
                    "edge=${"%.1f".format(liveEdgeNanos / 1_000_000.0)} ms audioPcm=${audioPcm?.size ?: 0}B.",
        )
        return true
    }

    /** Attaches live source while replay holds screen: live channel warms up as incoming channel in parallel. */
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
                    // — it streams the live PCM straight on, on its own native session, so the audio seam is
                    // continuous.
                    clock.rebaseTo(liveOffsetNanos)
                    audio.onBridgeHandoff() // Let the bridge session's (live-relative) clock drive pacing now
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

            val audioUrl = streamSet.currentAudio.url
            silentSession = audioUrl in SILENT_SOURCES
            if (silentSession) {
                // Silent source: nothing to bridge into, so retire the (silent) prelude session
                audio.stop()
            } else {
                // Whether or not a cached prelude is still playing, provideLiveInput hands the live URL
                // to whatever native session is current: a bridge session continues on it sample-
                // continuously (no gate, no flush, no second session); if there was no prelude, this is
                // a no-op and the fallback below opens a fresh, gated session instead.
                if (!audio.provideLiveInput(audioUrl)) {
                    val at = audio.start(
                        audioUrl, isHls = false, contentStartNanos = liveOffsetNanos,
                        startGate = firstLiveFrame, onUnexpectedEnd = onAudioFailure,
                    )
                    if (!at) logger.warn("$debugLabel [reappear] failed to open live audio after replay.")
                }
            }

            if (terminated.get()) {
                synchronized(switchLock) {
                    if (incoming === channel && incomingGeneration == generation) incoming = null
                }
                discardChannelBlocking(channel)
                return false
            }
            logger.debug("$debugLabel [reappear] live attached $w x $h at ${"%.1f".format(liveOffsetNanos / 1_000_000.0)} ms, warming up...")
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

    /** Captures live channel's entire encoded-packet cache (rolling window) for later replay. */
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
     * in-process-libav playback only, since a dormant pool member should not keep a decode session
     * tied up for an unbounded time.
     */
    fun canPark(): Boolean = canHoldWarm() && active?.inProcess == true

    /** Whether the session can hold its position warm at all: something is playing, and no replay bridge or quality switch is currently in flight. */
    private fun canHoldWarm(): Boolean =
        isPlaying && !terminated.get() && active != null &&
                bridgeCeilingNanos == Long.MAX_VALUE && incoming == null

    /** Parks live session: reader threads idle in place (decoder + audio session stay open, position frozen). */
    fun suspend(allowExternalProcess: Boolean = false): Boolean {
        if (!(if (allowExternalProcess) canHoldWarm() else canPark()) || parkFlag.get()) return false
        parkFlag.set(true)
        // Nothing may switch tracks while dormant, so holding idle sessions would be pure cost.
        audioWarmPool.invalidateAll()
        audio.pauseForPark()
        active?.pipe?.trimForPark()
        frozenPositionNanos = pacingClockNanos().takeIf { it >= 0L } ?: clock.currentTime()
        parkStartNanos = System.nanoTime()
        logger.debug("$debugLabel [park] session parked warm at ${"%.1f".format(frozenPositionNanos / 1_000_000.0)}ms.")
        return true
    }

    /** Un-parks suspended session: readers resume from frozen position; wall clock shifted past dormant interval. */
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
    fun captureAudioPcm(maxNanos: Long): NativeMedia.PcmSnapshot? {
        val guessRate = 48_000 // Upper-bound guess; snapshotPcm's own device format governs the actual count.
        val maxBytes = (maxNanos / 1_000_000_000.0 * guessRate * 2 * 4).toInt()
        return audio.snapshotPcm(maxBytes)?.takeIf { it.pcm.isNotEmpty() }
    }

    /**
     * The single clock every video pipe paces against. Owns session anchoring, the wall-time takeover
     * for a dead line, and the consistency of both across the several reader threads that sample it.
     */
    private val masterClock = AudioMasterClock(debugLabel, requestAudioResync = audio::requestResync)

    /** Master-clock position in nanos, or -1 when neither audio line nor wall clock is up yet. Audio drives pacing. */
    private fun pacingClockNanos(): Long {
        // While a replay -> live bridge is active the wall clock is clamped to the live edge so it never
        // overruns the handoff point (otherwise the live channel's first frame arrives "late" and is
        // dropped instead of presented, and the audio gate never opens).
        val wall = if (clock.isRunning) clock.currentTime().coerceAtMost(bridgeCeilingNanos) else -1L
        return masterClock.nanos(audio.sampleClock(), wall, parkFlag.get()) { null }
    }

    /** The position playback is actually at, for callers that need to freeze or save it. */
    fun currentPacingNanos(): Long = pacingClockNanos()

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
     * Seamless quality switch: launches [streamSet]'s new-quality video as a parallel incoming channel while the
     * current one keeps playing, then swaps once it's caught up.
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
                    "$debugLabel Starting incoming video handoff #$generation $w x $h " +
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
                !(!terminated.get() && active != null && incoming === channel && incomingGeneration == generation) && if (incoming === channel && incomingGeneration == generation) {
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
     * Signals all stop flags, closes the native audio session, and joins the video reader threads.
     * Tears down any in-flight quality switch too. Safe to call when idle.
     */
    fun stop() {
        isPlaying = false
        audioWarmPool.invalidateAll()
        bridgeCeilingNanos = Long.MAX_VALUE
        masterClock.reset()
        parkFlag.set(false) // Release any parked readers so they observe the stop flags and exit
        val inc = synchronized(switchLock) {
            incomingGeneration += 1
            incoming.also { incoming = null }
        }
        inc?.let { discardChannelBlocking(it) }

        val a = active
        active = null
        a?.let { it.stop.set(true); it.nativePipe?.kill() }
        audio.stop() // Releases a pending bridge live-input gate too; blocks until the decode thread joins
        a?.let {
            MediaProcess.gracefulDestroy(it.process)
            it.thread?.let { t -> joinSafely(t) }
            it.nativePipe?.release()
            renderExecutor.execute { it.pipe.cleanup() }
        }
    }

    /**
     * Releases any remaining pipe GL resources and stops the audio watcher thread. Called once when
     * this session manager is permanently discarded (the owning `MediaPlayer` is stopping for good).
     * [stop] normally clears channels first.
     */
    fun cleanup() {
        audioWarmPool.close()
        audio.close()
        synchronized(switchLock) { incoming.also { incoming = null } }?.let { discardChannelBlocking(it) }
        active?.let { ch ->
            active = null
            ch.nativePipe?.release()
            renderExecutor.execute { ch.pipe.cleanup() }
        }
    }
}
