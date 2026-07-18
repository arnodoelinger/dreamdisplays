package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.api.media.audio.AudioDspStage
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.pipeline.AudioSink.Companion.LINE_BUFFER_BYTES
import com.dreamdisplays.media.player.pipeline.AudioSink.Companion.MIN_PACE_BYTES
import com.dreamdisplays.media.player.pipeline.AudioSink.Companion.PCM_RING_MAX_BYTES
import com.dreamdisplays.media.player.util.MediaBufferEffects
import com.dreamdisplays.media.player.util.MediaUtil
import com.dreamdisplays.media.player.util.daemon
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*

/** Manages the `javax.sound` PCM pipeline for one `FFmpeg` audio process. */
internal class AudioSink(private val debugLabel: String) {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/AudioSink")

    companion object {
        /** The PCM sample rate used by every session: 44.1 kHz, stereo, signed 16-bit little-endian. */
        const val SAMPLE_RATE = 44100

        /** Stereo 16-bit PCM: 4 bytes per frame. One second is SAMPLE_RATE * BYTES_PER_FRAME bytes. */
        const val BYTES_PER_FRAME = 4

        /** Chunk size for each read from the audio process and each write to the line. 1 / 20 s of stereo 16-bit PCM */
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 * 2 / 20

        /**
         * Capacity of the PCM line (8 chunks, ~0.4 s). This is only the ceiling the pacer may grow into,
         * not the steady-state latency: [paceLiveWrite] holds the *unplayed* backlog near
         * [paceTargetBytes] (well below this), so DSP-to-ear latency tracks the adaptive target, while the
         * spare capacity is headroom the target can expand into on a stuttering machine.
         */
        private const val LINE_BUFFER_BYTES = CHUNK_BYTES * 8

        /** Smallest backlog the live pacer holds (~0.05 s) — the floor it settles back to when playback is clean. */
        private const val MIN_PACE_BYTES = CHUNK_BYTES

        /** Largest backlog the pacer will grow to (~0.30 s) before it stops trading latency for stability. */
        private const val MAX_PACE_BYTES = CHUNK_BYTES * 6

        /** Backlog step added per detected underrun / eased off per clean-playback window. */
        private const val PACE_STEP_BYTES = CHUNK_BYTES

        /** Consecutive clean chunks (~20 s at 20 chunks/s) before the pacer eases the target back down one step. */
        private const val PACE_RECOVER_CHUNKS = 400

        /** Number of times to retry opening the audio line if it is temporarily unavailable. */
        private const val OPEN_RETRIES = 3

        /** Retry delay between line-open attempts, multiplied by the attempt number (1..OPEN_RETRIES). */
        private const val RETRY_DELAY_MS = 200L

        /** ~30 s of stereo 16-bit PCM kept for the reappearance audio bridge. */
        private const val PCM_RING_MAX_BYTES = SAMPLE_RATE * BYTES_PER_FRAME * 30

        /** Content gaps below this (~1 video frame) aren't worth a catch-up skip — inaudible as lip sync. */
        private const val CATCHUP_MIN_NANOS = 40_000_000L

        /** Max refine passes of the catch-up skip; each pass shrinks the residual by the decode-speed factor. */
        private const val CATCHUP_MAX_PASSES = 4
    }

    /**
     * Content catch-up spec for an in-place audio restart (e.g. an audio-track switch): the new
     * process was seeked to [contentStartNanos], but by the time its PCM starts flowing the playback
     * clock ([playbackClock]) has moved on — the sink discards exactly that span of leading PCM before
     * playing, so the audible audio joins already aligned with the video instead of permanently
     * lagging by the process spawn + connect latency.
     */
    class CatchUp(val contentStartNanos: Long, val playbackClock: () -> Long)

    /** Current volume multiplier applied to each audio chunk. */
    @Volatile
    var currentVolume: Double = 1.0

    /**
     * Optional per-source acoustics DSP stage; when set it replaces [MediaBufferEffects.applyVolumeS16LE]
     * for every chunk (including the bridge prelude), receiving [currentVolume] as its bypass gain.
     */
    @Volatile
    private var dspStage: AudioDspStage? = null

    /** Installs (or clears) the acoustics DSP stage for this session; resets its state immediately. */
    fun setDspStage(stage: AudioDspStage?) {
        dspStage = stage
        stage?.reset()
    }

    /** Adaptive backlog the live pacer currently targets; persists across sessions to keep learning the machine. */
    private var paceTargetBytes = MIN_PACE_BYTES

    /** Consecutive underrun-free chunks since the last target change, used to ease the target back down. */
    private var paceCleanChunks = 0

    /** True once the first live chunk has been written, so an empty line before then isn't read as an underrun. */
    private var paceStreaming = false

    /**
     * Live-relative frame position of the open audio line, or -1 when no line is active (or the line is
     * still playing a bridge [preludeFrames] window). Used by [PlaybackClock.audioClockNanos] for A / V
     * sync. The cached prelude played ahead of the live PCM on a bridge line is subtracted so the clock
     * mapping (anchored at the live edge by `rebaseTo`) stays continuous across the cached -> live seam, and
     * video pacing keeps using the wall clock while the prelude is still playing.
     *
     * Stays -1 until live PCM has actually been read from the process ([pcmFlowing]): an open, started
     * line with nothing written reports frame position 0, which is a valid clock value — pacing would
     * freeze the whole video on it while the audio process is still connecting (or never delivers, as
     * happens with slow live HLS audio), dropping every frame after the max pacing wait.
     */
    val framePosition: Long
        get() {
            val ln = line ?: return -1L
            if (!exposeLiveClock || !pcmFlowing) return -1L
            val live = ln.longFramePosition - preludeFrames
            return if (live < 0L) -1L else live
        }

    /**
     * Monotonic counter bumped on every [start] / [startBridge], so clock consumers can tell a fresh
     * audio session (whose line position restarts at 0) from the one they last anchored against.
     */
    @Volatile
    var sessionEpoch = 0
        private set

    /** True once the current session has read at least one live PCM chunk from its process. */
    @Volatile
    private var pcmFlowing = false

    /** Source line for the current session, or null when no line is open. */
    @Volatile
    private var line: SourceDataLine? = null

    /** Frames of cached prelude played ahead of the live PCM on a bridge line (0 for a normal session). */
    @Volatile
    private var preludeFrames = 0L

    /**
     * Whether [framePosition] reports the line clock. False during a bridge's cached-prelude phase, so the
     * audio clock is hidden (it would still read the pre-handoff seek offset, stalling video pacing) until
     * [onBridgeHandoff] re-anchors the playback clock to the live edge. Always true for a normal session.
     */
    @Volatile
    private var exposeLiveClock = true

    /** Gate a bridge session waits on for its live `FFmpeg` process; null outside a bridge. */
    @Volatile
    private var liveGate: CountDownLatch? = null
    @Volatile
    private var liveProc: Process? = null

    /** When set and true, the reader pauses the line (keeping it open) and idles — used to keep the audio
     *  process warm while a display is parked out of render distance. Set once per session; persists across
     *  every audio path (normal start and the reappearance bridge) so any live audio thread observes it. */
    @Volatile
    private var parked: AtomicBoolean? = null

    /** Stderr buffer for the bridge's live process, set alongside [liveProc] by [provideLiveInput] and
     *  read by [runBridge] once it resumes past [awaitLiveInput] — see [drainStderr] for why each
     *  session gets its own buffer instead of a shared instance field. */
    @Volatile
    private var liveStderrBuf: StringBuilder? = null

    /** Installs the session park flag (see [parked]); set once by the owning session manager. */
    fun setParkFlag(flag: AtomicBoolean?) {
        parked = flag
    }

    /** Rolling ring of recently decoded raw PCM (newest last), so a reappearing display can play the
     *  cached-replay window with sound. Capped at [PCM_RING_MAX_BYTES]. Guarded by [pcmRing]'s monitor. */
    private val pcmRing = ArrayDeque<ByteArray>()
    private var pcmRingBytes = 0

    /** Appends a copy of the first [len] bytes of [chunk] to the PCM ring, evicting the oldest over budget. */
    private fun ringPush(chunk: ByteArray, len: Int) {
        if (len <= 0) return
        val copy = chunk.copyOf(len)
        synchronized(pcmRing) {
            pcmRing.addLast(copy)
            pcmRingBytes += len
            while (pcmRingBytes > PCM_RING_MAX_BYTES && pcmRing.size > 1) {
                pcmRingBytes -= pcmRing.removeFirst().size
            }
        }
    }

    /**
     * Drops all cached PCM. Called at the start of every fresh audio session ([start] / [startBridge])
     * so a seek or restart can never leave pre-seek samples in the ring.
     */
    private fun clearRing() {
        synchronized(pcmRing) {
            pcmRing.clear()
            pcmRingBytes = 0
        }
    }

    /**
     * Returns up to the most-recent [maxBytes] of audible cached PCM (oldest-to-newest), or empty when
     * none. PCM still queued in the line (decoded but not yet played) is excluded so the snapshot ends at
     * the heard position — keeping the cached audio aligned with the cached video when replayed.
     */
    fun snapshotPcm(maxBytes: Int): ByteArray {
        if (maxBytes <= 0) return ByteArray(0)
        val unplayed = line?.let { (it.bufferSize - it.available()).coerceAtLeast(0) } ?: 0
        synchronized(pcmRing) {
            // Round both bounds down to a whole stereo 16-bit frame so the slice never starts mid-sample
            // (unplayed comes from the line's byte counters and need not be frame-aligned).
            val end = ((pcmRingBytes - unplayed).coerceAtLeast(0) / BYTES_PER_FRAME) * BYTES_PER_FRAME
            val take = (end.coerceAtMost(maxBytes) / BYTES_PER_FRAME) * BYTES_PER_FRAME
            if (take <= 0) return ByteArray(0)
            val start = end - take
            val out = ByteArray(take)
            var idx = 0      // running byte offset of the current chunk's start
            var written = 0
            for (c in pcmRing) {
                val cStart = idx
                val cEnd = idx + c.size
                if (cEnd > start && cStart < end) {
                    val from = maxOf(start, cStart) - cStart
                    val to = minOf(end, cEnd) - cStart
                    System.arraycopy(c, from, out, written, to - from)
                    written += to - from
                }
                idx = cEnd
                if (idx >= end) break
            }
            // 4-byte (stereo 16-bit) frame alignment so playback never splits a sample.
            val misalign = written % BYTES_PER_FRAME
            return if (misalign == 0 && written == out.size) out else out.copyOfRange(misalign, written)
        }
    }

    /**
     * Starts the audio reading / writing loop.
     * @param onUnexpectedEnd called with the accumulated ffmpeg stderr if the audio process ends on its
     * own (crash, broken pipe, EOF) rather than via [stop] — never called for a deliberate teardown.
     * @param catchUp when set, leading PCM between the process's seek target and the live playback
     * clock is discarded before playback starts (see [CatchUp]).
     */
    fun start(
        proc: Process,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean,
        startGate: CountDownLatch? = null,
        onUnexpectedEnd: (String) -> Unit = {},
        catchUp: CatchUp? = null,
    ): Thread {
        preludeFrames = 0L
        liveGate = null
        exposeLiveClock = true
        pcmFlowing = false
        sessionEpoch++
        clearRing()
        dspStage?.reset()
        val stderrBuf = drainStderr(proc, stopFlag)
        return daemon(
            { run(proc, terminated, stopFlag, startGate, stderrBuf, onUnexpectedEnd, catchUp) }, "MediaPlayer-audio",
        ).also { it.start() }
    }

    /**
     * Prewarms a replacement line for a seamless in-place audio-track switch and, once it is primed,
     * flips it in for the currently playing line with no audible gap. Runs entirely on a background
     * thread; the live line is left untouched until the flip, so its clock, ring and pacing stay
     * valid while the replacement opens, applies its [catchUp] skip and pre-buffers leading PCM
     * (silent — not yet started). At the flip the old line is stopped and closed and the new one is
     * promoted and started from its primed buffer (instant sound), then [onPromoted] fires so the
     * caller can tear down the old half's process.
     *
     * The prime window carries volume only (no acoustics DSP) and is not ring-cached, to avoid racing
     * the still-live old session's shared DSP / ring state; both resume once the line is promoted.
     *
     * @param shouldPromote re-checked once the line is primed, right before the flip; when it returns
     * false (e.g. a newer switch has superseded this one) the flip is skipped and [onAborted] fires.
     * @param onPromoted invoked on the switch thread right after the flip (old line stopped, new one
     * playing) — the caller swaps ownership to the new half here.
     * @param onAborted invoked if the replacement never promotes (line open / prime failed, superseded,
     * or a stop fired first) so the caller can discard the new process and keep the current track.
     * @param onUnexpectedEnd see [start]; armed only once the promoted line begins its normal pump.
     */
    fun startSwitch(
        proc: Process,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean,
        catchUp: CatchUp?,
        shouldPromote: () -> Boolean = { true },
        onPromoted: () -> Unit,
        onAborted: () -> Unit = {},
        onUnexpectedEnd: (String) -> Unit = {},
    ): Thread {
        val stderrBuf = drainStderr(proc, stopFlag)
        return daemon(
            { runSwitch(proc, terminated, stopFlag, catchUp, shouldPromote, onPromoted, onAborted, stderrBuf, onUnexpectedEnd) },
            "MediaPlayer-audio-switch-line",
        ).also { it.start() }
    }

    /**
     * Background body of [startSwitch]: open + skip + pre-buffer the replacement line while the old
     * one plays, then atomically flip and continue as a normal live pump. See [startSwitch] for the
     * DSP / ring caveats on the prime window.
     */
    private fun runSwitch(
        proc: Process, terminated: AtomicBoolean, stopFlag: AtomicBoolean, catchUp: CatchUp?,
        shouldPromote: () -> Boolean, onPromoted: () -> Unit, onAborted: () -> Unit, stderrBuf: StringBuilder,
        onUnexpectedEnd: (String) -> Unit,
    ) {
        var newLine: SourceDataLine? = null
        var promoted = false
        var pumped = false

        fun isInterrupted() = terminated.get() || stopFlag.get()
        runCatching {
            proc.inputStream.use { input ->
                val fmt = pcmFormat()
                val info = DataLine.Info(SourceDataLine::class.java, fmt)
                if (!AudioSystem.isLineSupported(info)) {
                    logger.warn("$debugLabel PCM line not supported (switch).")
                    return@use
                }
                newLine = openLine(info, fmt) ?: run {
                    logger.warn("$debugLabel [audio] switch line failed to open.")
                    return@use
                }

                if (isInterrupted()) return@use
                catchUp?.let { skipCatchUp(input, it, terminated, stopFlag) }

                if (isInterrupted() || !primeSwitchLine(input, newLine, terminated, stopFlag)) return@use
                if (isInterrupted() || !shouldPromote()) return@use

                line?.apply {
                    runCatching { stop() }
                    runCatching { flush() }
                }.also { old ->
                    sessionEpoch++
                    clearRing()
                    dspStage?.reset()
                    preludeFrames = 0L
                    exposeLiveClock = true
                    pcmFlowing = true
                    line = newLine
                    newLine.start()

                    old?.let { runCatching { it.close() } }
                }

                promoted = true
                logger.debug("$debugLabel [audio] switch line promoted — new track playing.")
                onPromoted()

                pumpLive(input, newLine, terminated, stopFlag)
                pumped = true
            }
        }.onFailure { e ->
            if (isInterrupted()) return@onFailure
            when (e) {
                is IOException -> if (MediaPlayer.DEBUG) logger.warn("$debugLabel Switch read: ${e.message}.")
                else -> logger.warn("$debugLabel Switch pipeline: ${e.message}.")
            }
        }

        newLine?.let {
            if (line === it) line = null
            runCatching { it.flush() }
            runCatching { it.stop() }
            runCatching { it.close() }
        }

        if (!promoted) onAborted()

        if (pumped && !isInterrupted()) {
            onUnexpectedEnd(synchronized(stderrBuf) { stderrBuf.toString() })
        }
    }

    /**
     * Pre-buffers the replacement switch line with leading PCM before it is started (silent). Volume
     * is applied but not acoustics DSP, and nothing is ring-cached — the old session still owns that
     * shared state until the flip. Fills up to the pacer's learned backlog (clamped) so the promoted
     * line starts with a healthy buffer and no open-latency dropout. Returns false only when no PCM
     * arrived at all (immediate EOF) or a stop fired before anything was buffered.
     */
    private fun primeSwitchLine(
        input: InputStream, ln: SourceDataLine, terminated: AtomicBoolean, stopFlag: AtomicBoolean,
    ): Boolean {
        val chunk = ByteArray(CHUNK_BYTES)
        val primeTarget = paceTargetBytes.coerceIn(CHUNK_BYTES * 3, LINE_BUFFER_BYTES - CHUNK_BYTES)
        var buffered = 0
        while (buffered < primeTarget && !terminated.get() && !stopFlag.get()) {
            val n = MediaUtil.readFull(input, chunk, CHUNK_BYTES)
            if (n <= 0) return buffered > 0
            MediaBufferEffects.applyVolumeS16LE(chunk, n, currentVolume)
            writeFully(ln, chunk, n, terminated, stopFlag)
            buffered += n
        }
        return buffered > 0
    }

    /**
     * Starts a reappearance bridge on a single line: plays the cached [prelude] immediately, then — on the
     * same [SourceDataLine], with no flush and no second line — continues with the live PCM once
     * [provideLiveInput] supplies its process. The seam is therefore sample-continuous. [framePosition]
     * stays -1 until the line crosses out of the prelude, so video pacing is unaffected while it plays.
     * @param onUnexpectedEnd see [start].
     */
    fun startBridge(
        prelude: ByteArray,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean,
        onUnexpectedEnd: (String) -> Unit = {},
    ): Thread {
        preludeFrames = (prelude.size / BYTES_PER_FRAME).toLong()
        liveProc = null
        liveGate = CountDownLatch(1)
        exposeLiveClock = false
        pcmFlowing = false
        sessionEpoch++
        clearRing()
        dspStage?.reset()
        return daemon({ runBridge(prelude, terminated, stopFlag, onUnexpectedEnd) }, "MediaPlayer-audio-bridge").also { it.start() }
    }

    /** Supplies the live `FFmpeg` audio process to an in-flight bridge session (see [startBridge]). */
    fun provideLiveInput(proc: Process, stopFlag: AtomicBoolean) {
        liveStderrBuf = drainStderr(proc, stopFlag)
        liveProc = proc
        liveGate?.countDown()
    }

    /**
     * Marks the replay -> live handoff: the playback clock has just been re-anchored to the live edge, so
     * [framePosition] may now report the (live-relative) line clock. Called from the first-live-frame
     * callback, in lock-step with `PlaybackClock.rebaseTo`.
     */
    fun onBridgeHandoff() {
        exposeLiveClock = true
    }

    /** Flushes and closes the audio line immediately. Safe to call from any thread. */
    fun stop() {
        liveGate?.countDown() // Release a bridge thread still waiting for its live input
        val ln = line ?: return
        line = null
        runCatching { ln.flush() }
        runCatching { ln.stop() }
        runCatching { ln.close() }
    }

    /** Pauses playback for a warm park without closing or flushing the queued PCM. */
    fun pauseForPark() {
        line?.let { runCatching { it.stop() } }
    }

    /** Resumes a line paused by [pauseForPark]. */
    fun resumeFromPark() {
        line?.let { runCatching { it.start() } }
    }

    /**
     * Runs the audio reading / writing loop until the process ends or [terminated] / [stopFlag] is set.
     * Fires [onUnexpectedEnd] once the pump loop is reached and then ends on its own (neither flag set).
     */
    private fun run(
        proc: Process, terminated: AtomicBoolean, stopFlag: AtomicBoolean, startGate: CountDownLatch?,
        stderrBuf: StringBuilder, onUnexpectedEnd: (String) -> Unit, catchUp: CatchUp? = null,
    ) {
        var ln: SourceDataLine? = null
        var pumped = false
        runCatching {
            proc.inputStream.use { input ->
                val fmt = pcmFormat()
                val info = DataLine.Info(SourceDataLine::class.java, fmt)
                if (!AudioSystem.isLineSupported(info)) {
                    logger.warn("$debugLabel PCM line not supported.")
                    return@runCatching
                }
                ln = openLine(info, fmt) ?: run {
                    logger.warn("$debugLabel [audio] line failed to open.")
                    return@runCatching
                }
                if (terminated.get() || stopFlag.get()) return@runCatching
                logger.debug("$debugLabel [audio] line open, waiting for start gate...")
                if (!awaitStartGate(startGate, terminated, stopFlag)) {
                    logger.debug("$debugLabel [audio] aborted before start gate opened (terminated / stopped).")
                    return@runCatching
                }
                catchUp?.let { skipCatchUp(input, it, terminated, stopFlag) }
                if (terminated.get() || stopFlag.get()) return@runCatching
                ln.start()
                line = ln
                logger.debug("$debugLabel [audio] start gate passed, line started — audio is now playing.")
                pumpLive(input, ln, terminated, stopFlag)
                pumped = true
            }
        }.onFailure { e ->
            if (!terminated.get() && !stopFlag.get()) {
                if (e is IOException) {
                    if (MediaPlayer.DEBUG) logger.warn("$debugLabel Read: ${e.message}.")
                } else {
                    logger.warn("$debugLabel Pipeline: ${e.message}.")
                }
            }
        }

        ln?.let {
            if (line === it) line = null
            runCatching { it.flush() }
            runCatching { it.stop() }
            runCatching { it.close() }
        }

        if (pumped && !terminated.get() && !stopFlag.get()) {
            onUnexpectedEnd(synchronized(stderrBuf) { stderrBuf.toString() })
        }
    }

    /**
     * Runs a reappearance bridge on one line (see [startBridge]): opens the line, plays the cached
     * [prelude], then continues with the live PCM on the same line once it is attached. The prelude is
     * not ring-cached (it is already-played audio); only the live PCM feeds the rolling ring. Fires
     * [onUnexpectedEnd] once the live PCM pump is reached and then ends on its own (see [run]).
     */
    private fun runBridge(
        prelude: ByteArray, terminated: AtomicBoolean, stopFlag: AtomicBoolean,
        onUnexpectedEnd: (String) -> Unit,
    ) {
        var ln: SourceDataLine? = null
        var pumped = false
        var stderrBuf: StringBuilder? = null

        runCatching {
            val fmt = pcmFormat()
            val info = DataLine.Info(SourceDataLine::class.java, fmt)
            if (!AudioSystem.isLineSupported(info)) {
                logger.warn("$debugLabel PCM line not supported (bridge).")
                return@runCatching
            }
            ln = openLine(info, fmt) ?: run {
                logger.warn("$debugLabel [audio] bridge line failed to open.")
                return@runCatching
            }
            if (terminated.get() || stopFlag.get()) return@runCatching
            ln.start()
            line = ln
            val cachedSec = prelude.size / (SAMPLE_RATE * BYTES_PER_FRAME).toDouble()
            logger.debug("$debugLabel [audio] bridge line started; playing ${"%.2f".format(cachedSec)} s cached prelude.")

            // 1. Cached prelude — paced naturally by the line (not ring-cached: already-played audio)
            writePrelude(ln, prelude, terminated, stopFlag)

            // 2. Continue with the live PCM on the SAME line: sample-continuous, no flush, no second line
            val proc = awaitLiveInput(terminated, stopFlag) ?: run {
                logger.debug("$debugLabel [audio] bridge ended before live input attached.")
                return@runCatching
            }

            // provideLiveInput sets liveStderrBuf before liveProc, both before opening the gate this
            // just passed, so it is already populated for this bridge's live process here.
            stderrBuf = liveStderrBuf
            logger.debug("$debugLabel [audio] bridge handing off cached -> live on one line.")
            proc.inputStream.use { input -> pumpLive(input, ln, terminated, stopFlag) }
            pumped = true
        }.onFailure { e ->
            if (!terminated.get() && !stopFlag.get()) {
                logger.warn("$debugLabel Bridge pipeline: ${e.message}.")
            }
        }

        ln?.let {
            if (line === it) line = null
            runCatching { it.flush() }
            runCatching { it.stop() }
            runCatching { it.close() }
        }

        if (pumped && !terminated.get() && !stopFlag.get()) {
            onUnexpectedEnd(stderrBuf?.let { buf -> synchronized(buf) { buf.toString() } } ?: "")
        }
    }

    /** The PCM line format shared by every session: 44.1 kHz stereo signed 16-bit little-endian. */
    private fun pcmFormat() = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE.toFloat(), 16, 2, 4, SAMPLE_RATE.toFloat(), false,
    )

    /** Writes the cached bridge prelude to [ln] (volume applied), without ring-caching it. */
    private fun writePrelude(
        ln: SourceDataLine,
        prelude: ByteArray,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean
    ) {
        val chunk = ByteArray(CHUNK_BYTES)
        var off = 0
        while (off < prelude.size && !terminated.get() && !stopFlag.get()) {
            val n = minOf(CHUNK_BYTES, prelude.size - off)
            System.arraycopy(prelude, off, chunk, 0, n)
            dspStage?.process(chunk, n, currentVolume) ?: MediaBufferEffects.applyVolumeS16LE(chunk, n, currentVolume)
            writeFully(ln, chunk, n, terminated, stopFlag)
            off += n
        }
    }

    /** Polls the bridge's live-input gate until the process is attached, or stop/terminate aborts it. */
    private fun awaitLiveInput(terminated: AtomicBoolean, stopFlag: AtomicBoolean): Process? {
        val gate = liveGate ?: return null
        while (!terminated.get() && !stopFlag.get()) {
            try {
                if (gate.await(20L, TimeUnit.MILLISECONDS)) return liveProc
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return null
            }
        }
        return null
    }

    /**
     * Discards the leading PCM span between [CatchUp.contentStartNanos] and the live playback clock,
     * so the first sample actually played matches the video's current position. Runs before the line
     * starts playing (nothing here is audible, and none of it is ring-cached — it is never heard).
     * Iterative: each skip pass takes real time to read (the process decodes faster than realtime, so
     * passes converge), and the residual is re-measured against the clock until it is inaudible.
     */
    private fun skipCatchUp(input: InputStream, catchUp: CatchUp, terminated: AtomicBoolean, stopFlag: AtomicBoolean) {
        val scratch = ByteArray(CHUNK_BYTES)
        var skippedBytes = 0L
        repeat(CATCHUP_MAX_PASSES) {
            val contentNow = catchUp.contentStartNanos +
                    skippedBytes / BYTES_PER_FRAME * 1_000_000_000L / SAMPLE_RATE
            val gapNanos = catchUp.playbackClock() - contentNow
            if (gapNanos < CATCHUP_MIN_NANOS) {
                if (skippedBytes > 0L) {
                    val ms = skippedBytes / BYTES_PER_FRAME * 1000 / SAMPLE_RATE
                    logger.debug("$debugLabel [audio] catch-up skipped $ms ms of PCM to join in sync.")
                }
                return
            }
            var toSkip = gapNanos * SAMPLE_RATE / 1_000_000_000L * BYTES_PER_FRAME
            while (toSkip > 0 && !terminated.get() && !stopFlag.get()) {
                val n = MediaUtil.readFull(input, scratch, minOf(CHUNK_BYTES.toLong(), toSkip).toInt())
                if (n <= 0) return // EOF mid-skip: pump loop will report it
                skippedBytes += n
                toSkip -= n
            }
            if (terminated.get() || stopFlag.get()) return
        }
    }

    /** Reads live PCM from [input] and writes it to [ln], ring-caching each chunk for the next bridge. */
    private fun pumpLive(input: InputStream, ln: SourceDataLine, terminated: AtomicBoolean, stopFlag: AtomicBoolean) {
        val chunk = ByteArray(CHUNK_BYTES)
        var firstChunk = true
        var totalBytes = 0L
        // Fresh line for this session: don't let its initially-empty buffer read as an underrun on the
        // first paced write. The learned target itself persists across sessions.
        paceStreaming = false
        paceCleanChunks = 0
        while (!terminated.get() && !stopFlag.get()) {
            if (parkIfRequested(ln, terminated, stopFlag)) break
            val n = MediaUtil.readFull(input, chunk, CHUNK_BYTES)
            if (n <= 0) {
                // A deliberate teardown destroys the process mid-read; that EOF is expected and not
                // worth a warning (during a session-restart loop it reads as a phantom audio crash).
                if (terminated.get() || stopFlag.get()) break
                if (firstChunk) {
                    logger.warn("$debugLabel [audio] no PCM data from ffmpeg (EOF on first read).")
                } else {
                    val seconds = totalBytes / (SAMPLE_RATE * BYTES_PER_FRAME).toDouble()
                    logger.warn("$debugLabel [audio] PCM stream ended after ${"%.1f".format(seconds)} s.")
                }
                break
            }
            totalBytes += n
            if (firstChunk) {
                logger.debug("$debugLabel [audio] first PCM chunk received ($n bytes)."); firstChunk = false
                pcmFlowing = true
            }
            ringPush(chunk, n) // Cache raw PCM (pre-volume) for the reappearance audio bridge
            dspStage?.process(chunk, n, currentVolume) ?: MediaBufferEffects.applyVolumeS16LE(chunk, n, currentVolume)
            paceLiveWrite(ln, chunk, n, terminated, stopFlag)
        }
    }

    /**
     * If the session is parked, pauses the line (keeping its buffer + open handle), idles until un-parked,
     * then resumes the line. The audio `FFmpeg` process blocks on pipe back-pressure meanwhile, staying
     * warm. Returns true only when stop/terminate fired while parked (caller should break the loop).
     */
    private fun parkIfRequested(ln: SourceDataLine, terminated: AtomicBoolean, stopFlag: AtomicBoolean): Boolean {
        val pk = parked ?: return false
        if (!pk.get()) return false
        runCatching { ln.stop() } // Pause playback, keep the buffered tail and the line open
        while (pk.get() && !terminated.get() && !stopFlag.get()) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return true
            }
        }
        if (terminated.get() || stopFlag.get()) return true
        runCatching { ln.start() } // Resume from exactly where it paused (frame position is continuous)
        return false
    }

    /** Writes the first [n] bytes of [chunk] to [ln], retrying short writes until stop/terminate. */
    private fun writeFully(
        ln: SourceDataLine,
        chunk: ByteArray,
        n: Int,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean
    ) {
        var written = 0
        while (written < n && !terminated.get() && !stopFlag.get()) {
            val w = ln.write(chunk, written, n - written)
            if (w <= 0) break
            written += w
        }
    }

    /**
     * Writes one live chunk while keeping the line's *unplayed* backlog near [paceTargetBytes] instead of
     * letting it fill the whole [LINE_BUFFER_BYTES] capacity, so DSP-to-ear latency (and thus how quickly
     * acoustics track head / position changes) stays close to the target rather than the buffer size.
     *
     * The target is self-adapting: if the line drained to empty while this chunk was being produced (an
     * underrun), it grows a step to buy more headroom; after a long clean-playback window it eases back
     * one step toward [MIN_PACE_BYTES]. So it settles at the smallest backlog the machine can actually
     * sustain without stuttering. A / V sync is unaffected — [framePosition] tracks the line's playout
     * clock, not how far ahead we queue.
     */
    private fun paceLiveWrite(
        ln: SourceDataLine,
        chunk: ByteArray,
        n: Int,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean
    ) {
        val unplayedBefore = (ln.bufferSize - ln.available()).coerceAtLeast(0)
        if (paceStreaming) {
            if (unplayedBefore == 0) {
                paceTargetBytes = (paceTargetBytes + PACE_STEP_BYTES).coerceAtMost(MAX_PACE_BYTES)
                paceCleanChunks = 0
            } else if (++paceCleanChunks >= PACE_RECOVER_CHUNKS) {
                paceTargetBytes = (paceTargetBytes - PACE_STEP_BYTES).coerceAtLeast(MIN_PACE_BYTES)
                paceCleanChunks = 0
            }
        }
        writeFully(ln, chunk, n, terminated, stopFlag)
        paceStreaming = true
        // Hold the backlog at the target: wait for playout to drain the surplus before feeding the next chunk.
        while (!terminated.get() && !stopFlag.get()) {
            if ((ln.bufferSize - ln.available()).coerceAtLeast(0) <= paceTargetBytes) break
            try {
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); break
            }
        }
    }

    /** Waits for video to publish its first frame, polling so stop() can interrupt quickly. */
    private fun awaitStartGate(
        gate: CountDownLatch?,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean,
    ): Boolean {
        if (gate == null || gate.count == 0L) return true
        while (!terminated.get() && !stopFlag.get()) {
            try {
                if (gate.await(50L, TimeUnit.MILLISECONDS)) return true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /**
     * Opens and returns a [SourceDataLine] with the specified format, retrying a few times if the line is temporarily unavailable.
     */
    private fun openLine(info: DataLine.Info, fmt: AudioFormat): SourceDataLine? {
        repeat(OPEN_RETRIES) { attempt ->
            try {
                return (AudioSystem.getLine(info) as SourceDataLine).also { it.open(fmt, LINE_BUFFER_BYTES) }
            } catch (e: LineUnavailableException) {
                if (attempt == OPEN_RETRIES - 1) {
                    logger.warn("$debugLabel Line unavailable: ${e.message}.")
                    return null
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); return null
                }
            }
        }
        return null
    }

    /**
     * Drains [proc]'s stderr, logs interesting lines FFmpeg emits as they arrive (it runs at
     * -loglevel error), and accumulates them into a buffer scoped to this call — returned so the
     * caller's session can read it back once its own pump loop ends.
     */
    private fun drainStderr(proc: Process, stopFlag: AtomicBoolean): StringBuilder {
        val buf = StringBuilder()
        daemon({
            try {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) {
                        val trimmed = line.trim()
                        if (stopFlag.get()) {
                            logger.debug("$debugLabel [audio] FFmpeg stderr during teardown: $trimmed.")
                        } else if (MediaUtil.isInterestingStderr(trimmed)) {
                            logger.warn("$debugLabel [audio] FFmpeg stderr: $trimmed.")
                        }
                        synchronized(buf) { buf.append(line).append('\n') }
                    }
                }
            } catch (_: IOException) {
            }
        }, "MediaPlayer-astderr").start()
        return buf
    }
}
