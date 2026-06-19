package com.dreamdisplays.player.managers

import com.dreamdisplays.player.process.FFmpegBinary
import com.dreamdisplays.media.MediaHostGuard
import com.dreamdisplays.media.api.DreamMediaException
import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.player.events.PlayerEvents
import com.dreamdisplays.player.nativebridge.NativeMedia
import com.dreamdisplays.player.pipeline.AudioSink
import com.dreamdisplays.player.pipeline.FramePipe
import com.dreamdisplays.player.pipeline.NativeVideoFramePipe
import com.dreamdisplays.player.pipeline.PlaybackClock
import com.dreamdisplays.player.pipeline.VideoFramePipe
import com.dreamdisplays.player.process.HwAccelBackend
import com.dreamdisplays.player.process.MediaProcess
import com.dreamdisplays.player.stream.MediaStreamSelector
import com.dreamdisplays.player.stream.ActiveStreams
import com.dreamdisplays.player.util.MediaUtil
import com.dreamdisplays.render.UploadPixelFormat
import com.dreamdisplays.player.util.daemon
import com.dreamdisplays.player.util.joinSafely
import com.mojang.blaze3d.textures.GpuTexture
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
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

    /** Invoked when an in-flight quality switch fails before promotion, so the caller can drop the staged texture. */
    private val onQualitySwitchAborted: () -> Unit = {},
) {
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
            if (NativeMedia.isAvailable) NativeVideoFramePipe(debugLabel) else null
        private val jvmPipe: VideoFramePipe? = if (nativePipe == null) VideoFramePipe(debugLabel) else null
        val pipe: FramePipe = nativePipe ?: jvmPipe!!

        @Volatile var process: Process? = null
        @Volatile var thread: Thread? = null

        /** True when this channel decodes via the in-process libav path — the only path that supports a
         *  warm park (the others use an external `FFmpeg` process / pipe). */
        @Volatile var inProcess = false
            private set
        val stop = AtomicBoolean()

        /**
         * Launches the video decode (in-process libav, native `FFmpeg`, or pure-JVM `FFmpeg`, in that
         * order of preference) into this channel's pipe. Throws [IOException] when the native session
         * cannot be opened. Never touches audio or the clock. [parkFlag] is honored only on the in-process
         * libav path (the warm-parkable one).
         */
        fun launch(
            ffmpeg: String, streamSet: ActiveStreams, w: Int, h: Int, offsetNanos: Long,
            hwAccel: HwAccelBackend, onFirstFrame: () -> Unit, onEos: (String, Boolean) -> Unit,
            getAudioClock: () -> Long = ::pacingClockNanos, parkFlag: AtomicBoolean? = null,
        ) {
            // SSRF guard for the in-process libav path, which bypasses MediaProcess.baseCommand
            MediaHostGuard.requireAllowed(streamSet.currentVideo.url)
            val fps = streamSet.currentVideo.fps ?: 30.0
            val lavThread = if (nativePipe != null && NativeMedia.lavInProcessEnabled) {
                nativePipe.startInProcess(
                    url = streamSet.currentVideo.url, w = w, h = h, seekOffsetNanos = offsetNanos,
                    sourceFps = fps, hwAccel = hwAccel, stopFlag = stop, terminated = terminated,
                    getAudioClock = getAudioClock, onFirstFrame = onFirstFrame,
                    getBrightness = getBrightness, onEos = onEos, parkFlag = parkFlag,
                )
            } else null
            if (lavThread != null) {
                process = null; thread = lavThread; inProcess = true; return
            }
            if (nativePipe != null) {
                val nv12 = NativeMedia.nv12Enabled
                val transport = if (nv12) MediaProcess.VideoTransport.RAW_NV12 else MediaProcess.VideoTransport.RAW_RGB24
                val args = MediaProcess.videoArgs(ffmpeg, streamSet.currentVideo.url, w, h, offsetNanos, hwAccel, transport)
                val vt = nativePipe.start(
                    args = args, w = w, h = h, nv12 = nv12, seekOffsetNanos = offsetNanos, sourceFps = fps,
                    stopFlag = stop, terminated = terminated, getAudioClock = getAudioClock,
                    onFirstFrame = onFirstFrame, getBrightness = getBrightness, onEos = onEos,
                ) ?: throw IOException("Native FFmpeg session failed to start")
                process = null; thread = vt; return
            }
            val vp = MediaProcess.buildVideo(ffmpeg, streamSet.currentVideo.url, w, h, offsetNanos, hwAccel)
            val vt = jvmPipe!!.start(
                proc = vp, w = w, h = h, seekOffsetNanos = offsetNanos, sourceFps = fps,
                stopFlag = stop, terminated = terminated, getAudioClock = getAudioClock,
                onFirstFrame = onFirstFrame, getBrightness = getBrightness, onEos = onEos,
            )
            process = vp; thread = vt
        }

        /** Captures this channel's live LAV packet-ring snapshot, when one exists. */
        fun snapshotCache(positionNanos: Long): ByteArray? = nativePipe?.lavCacheSnapshot(positionNanos)

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

    /** The single-line reappearance bridge audio session (cached prelude -> live PCM on one line) while it
     *  is still playing its prelude, before the live process is attached and it moves into [audioHalf]. */
    @Volatile private var bridgeAudio: AudioHalf? = null

    /** When true, the live video + audio reader threads idle in place, keeping their decoder / line open
     *  so a returning display resumes instantly ([suspend] / [resume]). Reset false on every fresh start. */
    private val parkFlag = AtomicBoolean(false)

    init {
        // The single audio sink observes the session park flag across every audio path (normal + bridge).
        audio.setParkFlag(parkFlag)
    }

    /** Guards the live/incoming channel transitions across the control, render, and reader threads. */
    private val switchLock = Any()
    @Volatile private var active: VideoChannel? = null
    @Volatile private var incoming: VideoChannel? = null
    @Volatile private var incomingGeneration: Long = 0L
    @Volatile private var audioHalf: AudioHalf? = null

    /** Fallback timestamp source when no channel is live (the watchdog guards against reading it then). */
    private val noFrames = AtomicLong(0)

    /**
     * Upper bound the shared wall clock is clamped to while a replay -> live bridge is in flight (the live
     * edge the live channel resumes at). Both the replay channel and the warming-up live channel pace
     * on this one clamped clock, so the live channel's first frame becomes due *exactly* when replay
     * reaches the edge — the handoff has no forward jump and no drop-storm. On the live channel's first
     * frame the clock is rebased to the edge and this is lifted to [Long.MAX_VALUE]. Default disables it.
     */
    @Volatile private var bridgeCeilingNanos: Long = Long.MAX_VALUE

    @Volatile var isPlaying = false
        private set

    /** True once the first decoded frame of the live channel is ready for GPU upload. */
    fun textureFilled(): Boolean = active?.pipe?.textureFilled() == true

    /** Uploads the latest live frame to [texture]. Returns true if a frame was uploaded. Render thread only. */
    fun updateFrame(texture: GpuTexture, w: Int, h: Int): Boolean = active?.pipe?.updateFrame(texture, w, h) == true

    /** Uploads the latest live planar I420 frame into the three plane textures. Returns true if uploaded. */
    fun updateFramePlanar(y: GpuTexture, u: GpuTexture, v: GpuTexture, w: Int, h: Int): Boolean =
        active?.pipe?.updateFramePlanar(y, u, v, w, h) == true

    /** True while an incoming (quality-switch) channel is warming up in parallel. */
    fun hasIncoming(): Boolean = incoming != null

    /** Uploads the latest incoming-channel frame to [texture] (the staged texture). Returns true if uploaded. */
    fun updateIncomingFrame(texture: GpuTexture, w: Int, h: Int): Boolean =
        incoming?.pipe?.updateFrame(texture, w, h) == true

    /** Uploads the latest incoming-channel planar I420 frame into the staged plane textures. */
    fun updateIncomingFramePlanar(y: GpuTexture, u: GpuTexture, v: GpuTexture, w: Int, h: Int): Boolean =
        incoming?.pipe?.updateFramePlanar(y, u, v, w, h) == true

    /** Discards the live channel's ready frame. Call when stopping or seeking. */
    fun clearFrame() = active?.pipe?.clear() ?: Unit

    /** Frame position of the open audio line, or -1 when no line is active. */
    val audioFramePosition: Long get() = audio.framePosition

    /** Sets the effective volume (user volume * distance attenuation). */
    fun setVolume(volume: Double) { audio.currentVolume = volume }

    /** Timestamp of the live channel's last decoded video frame; read by [StreamWatchdog]. */
    val lastFrameNanos: AtomicLong get() = active?.pipe?.lastFrameReceivedNanos ?: noFrames

    @Volatile private var popoutSink: ((ByteBuffer, Int, Int, UploadPixelFormat) -> Unit)? = null
    @Volatile private var previewSink: ((ByteBuffer, Int, Int, UploadPixelFormat) -> Unit)? = null

    /** Routes raw frames to the popout window. Null = no popout active. */
    var popoutFrameSink: ((ByteBuffer, Int, Int, UploadPixelFormat) -> Unit)?
        get() = popoutSink
        set(value) {
            popoutSink = value
            updateRawFrameSink()
        }

    /** Routes raw frames to the display menu preview when the main texture is GPU-YUV only. */
    var previewFrameSink: ((ByteBuffer, Int, Int, UploadPixelFormat) -> Unit)?
        get() = previewSink
        set(value) {
            previewSink = value
            updateRawFrameSink()
        }

    private fun updateRawFrameSink() {
        val popout = popoutSink
        val preview = previewSink
        val sink: ((ByteBuffer, Int, Int, UploadPixelFormat) -> Unit)? =
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
     */
    fun start(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int, hwAccel: HwAccelBackend) {
        stop()
        if (terminated.get()) return
        bridgeCeilingNanos = Long.MAX_VALUE // a full start is not a bridge

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
                MediaProcess.buildAudio(ffmpeg, streamSet.currentAudio.url, offsetNanos, AudioSink.SAMPLE_RATE)
            } catch (e: IOException) {
                // The video side is already running; tear it down before propagating
                channel.teardownProcess()
                throw e
            }
            val at = audio.start(ap, terminated, aStop, startGate = firstVideoFrame)
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
     * Starts cached replay video alone — no audio, no network — so a reappearing display shows frames
     * instantly. Resumes at [resumeNanos] and plays toward [liveEdgeNanos] (the saved position the live
     * source will resume at). The wall clock is clamped to [liveEdgeNanos] so replay never overruns
     * the handoff point; [attachLiveAfterReplay] takes over there. Returns false when native replay is
     * unavailable.
     */
    fun startReplayVideoOnly(snapshot: ByteArray, resumeNanos: Long, liveEdgeNanos: Long, audioPcm: ByteArray?): Boolean {
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
            val at = audio.startBridge(audioPcm, terminated, aStop)
            bridgeAudio = AudioHalf(null, at, aStop)
        }
        updateRawFrameSink()
        isPlaying = true
        logger.debug(
            "$debugLabel [reappear] replay-only video started ${w} x $h resume=${"%.1f".format(resumeNanos / 1_000_000.0)}ms " +
                    "edge=${"%.1f".format(liveEdgeNanos / 1_000_000.0)}ms audioPcm=${audioPcm?.size ?: 0}B.",
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
            channel.launch(ffmpeg, streamSet, w, h, liveOffsetNanos, hwAccel, onFirstFrame = {
                // Live reached the edge: re-anchor the clock there (matching the audio offset), lift the
                // bridge clamp, then open the live audio gate. The cached bridge audio is not stopped here
                // — it streams the live PCM straight on, on its own line, so the audio seam is continuous.
                clock.rebaseTo(liveOffsetNanos)
                audio.onBridgeHandoff() // Let the bridge line's (live-relative) clock drive pacing now
                bridgeCeilingNanos = Long.MAX_VALUE
                firstLiveFrame.countDown()
                logger.debug("$debugLabel [reappear] live channel presented first frame; handoff at ${"%.1f".format(liveOffsetNanos / 1_000_000.0)}ms.")
            }, onEos = { stderr, normalEos -> abortIncoming(generation, "eos=$normalEos stderr=${MediaUtil.truncate(stderr)}") },
                parkFlag = parkFlag)

            val ap = MediaProcess.buildAudio(ffmpeg, streamSet.currentAudio.url, liveOffsetNanos, AudioSink.SAMPLE_RATE)
            val bridge = bridgeAudio
            if (bridge != null) {
                // Cached prelude is playing on the bridge line: feed the live PCM to that same line, so it
                // continues sample-continuously off the prelude (no gate, no flush, no second line).
                audio.provideLiveInput(ap)
                audioHalf = AudioHalf(ap, bridge.thread, bridge.stop)
                bridgeAudio = null
            } else {
                // No cached prelude: live audio joins at the edge, gated on the first live frame (as in start()).
                val aStop = AtomicBoolean()
                val at = audio.start(ap, terminated, aStop, startGate = firstLiveFrame)
                audioHalf = AudioHalf(ap, at, aStop)
            }

            if (terminated.get()) {
                synchronized(switchLock) { if (incoming === channel && incomingGeneration == generation) incoming = null }
                discardChannelBlocking(channel)
                return false
            }
            logger.debug("$debugLabel [reappear] live attached ${w} x $h at ${"%.1f".format(liveOffsetNanos / 1_000_000.0)}ms, warming up...")
            true
        } catch (e: IOException) {
            logger.error("$debugLabel [reappear] failed to attach live after replay", e)
            val wasCurrent = synchronized(switchLock) {
                if (incoming === channel && incomingGeneration == generation) { incoming = null; true } else false
            }
            discardChannelBlocking(channel)
            if (wasCurrent) onQualitySwitchAborted()
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

    @Volatile private var parkStartNanos = 0L
    @Volatile private var frozenPositionNanos = -1L

    /**
     * Whether this session can be parked warm (steady in-process-libav playback): no external `FFmpeg`
     * process to keep fed, no replay bridge or quality switch in flight. Only that path keeps the decoder
     * open and idle on a held position so a returning display resumes instantly.
     */
    fun canPark(): Boolean =
        isPlaying && !terminated.get() && active?.inProcess == true &&
            bridgeCeilingNanos == Long.MAX_VALUE && incoming == null && audioHalf != null

    /**
     * Parks the live session: the video + audio reader threads idle in place (decoder + audio line stay
     * open, position frozen), so [resume] continues instantly without re-resolving or cold-decoding.
     * Returns false when the session is not in a parkable state (caller should tear down instead).
     */
    fun suspend(): Boolean {
        if (!canPark() || parkFlag.get()) return false
        val fp = audio.framePosition
        frozenPositionNanos = if (fp >= 0) clock.audioClockNanos(fp, AudioSink.SAMPLE_RATE) else clock.currentTime()
        parkStartNanos = System.nanoTime()
        parkFlag.set(true)
        active?.pipe?.trimForPark()
        logger.debug("$debugLabel [park] session parked warm at ${"%.1f".format(frozenPositionNanos / 1_000_000.0)}ms.")
        return true
    }

    /** Un-parks a [suspend]ed session: the readers resume from the frozen position; the wall clock is
     *  shifted past the dormant interval so the position continues instead of jumping ahead. */
    fun resume() {
        if (!parkFlag.get()) return
        parkFlag.set(false)
        frozenPositionNanos = -1L
        clock.addPausedDuration(System.nanoTime() - parkStartNanos)
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

    /** Audio-clock pacing position in nanos, or -1 when neither the audio line nor the wall clock is up yet. */
    private fun pacingClockNanos(): Long {
        val fp = audio.framePosition
        if (fp >= 0) return clock.audioClockNanos(fp, AudioSink.SAMPLE_RATE)
        if (!clock.isRunning) return -1L
        // While a replay->live bridge is active the wall clock is clamped to the live edge so it never
        // overruns the handoff point (otherwise the live channel's first frame arrives "late" and is
        // dropped instead of presented, and the audio gate never opens).
        return clock.currentTime().coerceAtMost(bridgeCeilingNanos)
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
            // Nothing to hand off from: drop the staged texture so the full start uses the live dims
            onQualitySwitchAborted()
            start(streamSet, offsetNanos, lastQuality, hwAccel)
            return
        }
        val ffmpeg = FFmpegBinary.getPath() ?: run { onQualitySwitchAborted(); return }

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
                    "$debugLabel Starting incoming video handoff #$generation ${w}x$h " +
                            "at ${"%.1f".format(offsetNanos / 1_000_000.0)}ms.",
                )
            }
            channel.launch(ffmpeg, streamSet, w, h, offsetNanos, hwAccel, onFirstFrame = {
                clock.markFirstFrame()
                if (MediaPlayer.DEBUG) logger.debug("$debugLabel Incoming video handoff #$generation presented its first frame.")
            }, onEos = { stderr, normalEos -> abortIncoming(generation, "eos=$normalEos stderr=${MediaUtil.truncate(stderr)}") },
                parkFlag = parkFlag)
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
            logger.error("$debugLabel Failed to start incoming video for quality switch", e)
            val wasCurrent = synchronized(switchLock) {
                if (incoming === channel && incomingGeneration == generation) {
                    incoming = null
                    true
                } else {
                    false
                }
            }
            discardChannelBlocking(channel)
            if (wasCurrent) onQualitySwitchAborted()
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

    /** Aborts an in-flight quality switch (incoming EOS/failure): drops the incoming channel, keeps the live one. */
    private fun abortIncoming(generation: Long, reason: String) {
        val inc = synchronized(switchLock) {
            if (incomingGeneration != generation) null else incoming.also { incoming = null }
        } ?: return
        if (MediaPlayer.DEBUG) logger.debug("$debugLabel Aborted incoming video handoff #$generation ($reason).")
        discardChannelAsync(inc)
        onQualitySwitchAborted()
    }

    /** Tears down [channel] (process join) on a background thread, then releases its GL resources on the render thread. */
    private fun discardChannelAsync(channel: VideoChannel) {
        daemon({
            channel.teardownProcess()
            Minecraft.getInstance().execute { channel.pipe.cleanup() }
        }, "MediaPlayer-video-discard").start()
    }

    /** Tears down [channel] inline (caller must not be the render thread), then frees its GL resources. */
    private fun discardChannelBlocking(channel: VideoChannel) {
        channel.teardownProcess()
        Minecraft.getInstance().execute { channel.pipe.cleanup() }
    }

    /**
     * Signals all stop flags, destroys the `FFmpeg` processes, closes the audio line, and joins the
     * reader threads. Tears down any in-flight quality switch too. Safe to call when idle.
     */
    fun stop() {
        isPlaying = false
        bridgeCeilingNanos = Long.MAX_VALUE
        parkFlag.set(false) // release any parked readers so they observe the stop flags and exit
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
            Minecraft.getInstance().execute { it.pipe.cleanup() }
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
            Minecraft.getInstance().execute { ch.pipe.cleanup() }
        }
    }
}
