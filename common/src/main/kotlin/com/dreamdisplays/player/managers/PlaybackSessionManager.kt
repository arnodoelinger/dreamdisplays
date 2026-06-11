package com.dreamdisplays.player.managers

import com.dreamdisplays.player.process.FFmpegBinary
import com.dreamdisplays.media.api.DreamMediaException
import com.dreamdisplays.player.events.PlayerEvents
import com.dreamdisplays.player.pipeline.AudioSink
import com.dreamdisplays.player.pipeline.PlaybackClock
import com.dreamdisplays.player.pipeline.VideoFramePipe
import com.dreamdisplays.player.process.HwAccelBackend
import com.dreamdisplays.player.process.MediaProcess
import com.dreamdisplays.player.stream.MediaStreamSelector
import com.dreamdisplays.player.stream.ActiveStreams
import com.dreamdisplays.player.util.joinSafely
import com.mojang.blaze3d.textures.GpuTexture
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns and manages the lifecycle of one `FFmpeg` video + audio session: processes, reader threads,
 * stop flags, [AudioSink], and [VideoFramePipe].
 *
 * `MediaPlayer` calls [start] / [stop] and
 * delegates rendering queries here. The [StreamWatchdog] is coordinated by `MediaPlayer` externally.
 */
internal class PlaybackSessionManager(
    private val debugLabel: String,
    private val clock: PlaybackClock,
    private val events: PlayerEvents,
    private val terminated: AtomicBoolean,

    /** Returns the current GPU texture dimensions (width to height). */
    private val getTextureSize: () -> Pair<Int, Int>,
    private val getBrightness: () -> Double,

    /** Invoked by [VideoFramePipe] when the stream ends or errors. Called on the reader thread. */
    private val onStreamEnd: (stderr: String, normalEos: Boolean) -> Unit,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/PlaybackSession")

    private data class Session(
        val videoProcess: Process,
        val audioProcess: Process,
        val videoThread: Thread,
        val audioThread: Thread,
        val videoStop: AtomicBoolean,
        val audioStop: AtomicBoolean,
    )

    private val audio = AudioSink(debugLabel)
    private val video = VideoFramePipe(debugLabel)

    @Volatile var isPlaying = false
        private set

    @Volatile private var session: Session? = null

    /** True once the first decoded frame is ready for GPU upload. */
    fun textureFilled(): Boolean = video.textureFilled()

    /** Uploads the latest frame to [texture]. Must be called from the render thread. */
    fun updateFrame(texture: GpuTexture, w: Int, h: Int) = video.updateFrame(texture, w, h)

    /** Discards the current ready frame. Call when stopping or seeking. */
    fun clearFrame() = video.clear()

    /** Frame position of the open audio line, or -1 when no line is active. */
    val audioFramePosition: Long get() = audio.framePosition

    /** Sets the effective volume (user volume * distance attenuation). */
    fun setVolume(volume: Double) { audio.currentVolume = volume }

    /** Timestamp of the last decoded video frame; read by [StreamWatchdog]. */
    val lastFrameNanos: AtomicLong get() = video.lastFrameReceivedNanos

    /** Routes raw RGB frames to the popout window. Null = no popout active. */
    var popoutFrameSink: ((java.nio.ByteBuffer, Int, Int) -> Unit)?
        get() = video.popoutFrameSink
        set(value) { video.popoutFrameSink = value }

    /**
     * Stops any running session, then launches new `FFmpeg` processes for [streamSet]
     * starting at [offsetNanos]. Wires up the clock, brightness, and EOS callbacks.
     *
     * @param lastQuality last confirmed quality in pixels; 0 = derive from stream metadata
     */
    fun start(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int, hwAccel: HwAccelBackend) {
        stop()
        if (terminated.get()) return
        video.clear()

        val ffmpeg = FFmpegBinary.getPath() ?: run {
            logger.error("$debugLabel FFmpeg binary not available.")
            events.onError(DreamMediaException.Decode("FFmpeg binary not available", isFatal = true)); return
        }
        clock.reset(offsetNanos)

        val (tw, th) = getTextureSize()
        val q = if (lastQuality > 0) lastQuality else MediaStreamSelector.parseQuality(streamSet.currentVideo)
        val (w, h) = if (tw > 0 && th > 0) tw to th
                     else MediaStreamSelector.qualityToDims(q).let { it[0] to it[1] }

        try {
            val vStop = AtomicBoolean(); val aStop = AtomicBoolean()
            val vp = MediaProcess.buildVideo(ffmpeg, streamSet.currentVideo.url, w, h, offsetNanos, hwAccel)
            val ap = MediaProcess.buildAudio(ffmpeg, streamSet.currentAudio.url, offsetNanos, AudioSink.SAMPLE_RATE)
            val vt = video.start(
                proc = vp, w = w, h = h, seekOffsetNanos = offsetNanos,
                sourceFps = streamSet.currentVideo.fps ?: 30.0,
                stopFlag = vStop, terminated = terminated,
                getAudioClock = { clock.audioClockNanos(audio.framePosition, AudioSink.SAMPLE_RATE) },
                onFirstFrame = { clock.markFirstFrame() },
                getBrightness = getBrightness,
                onEos = onStreamEnd,
            )
            val at = audio.start(ap, terminated, aStop)
            session = Session(vp, ap, vt, at, vStop, aStop)
            isPlaying = true
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start FFmpeg", e)
            events.onError(DreamMediaException.Decode("Failed to start FFmpeg: ${e.message}", e))
        }
    }

    /**
     * Signals both stop flags, destroys the `FFmpeg` processes, closes the audio line,
     * and joins the reader threads. Safe to call when no session is active.
     */
    fun stop() {
        isPlaying = false
        session?.let { s ->
            s.videoStop.set(true); s.audioStop.set(true)
            MediaProcess.gracefulDestroy(s.videoProcess)
            MediaProcess.gracefulDestroy(s.audioProcess)
            audio.stop()
            joinSafely(s.videoThread); joinSafely(s.audioThread)
        }
        session = null
    }

    /**
     * Releases the PBO ring held by [video]. Must be called once when this session manager is
     * permanently discarded (i.e., when the owning `MediaPlayer` is stopping for good).
     * Schedules the GL cleanup on the render thread.
     */
    fun cleanup() {
        Minecraft.getInstance().execute { video.cleanup() }
    }
}
