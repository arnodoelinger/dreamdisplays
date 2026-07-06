package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.util.MediaBufferEffects
import com.dreamdisplays.media.player.util.MediaUtil
import com.dreamdisplays.media.player.util.daemon
import org.slf4j.LoggerFactory
import java.io.IOException
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

        /** Line buffer size for the PCM line: 10 chunks, ~0.5 s of stereo 16-bit PCM. */
        private const val LINE_BUFFER_BYTES = CHUNK_BYTES * 10

        /** Number of times to retry opening the audio line if it is temporarily unavailable. */
        private const val OPEN_RETRIES = 3

        /** Retry delay between line-open attempts, multiplied by the attempt number (1..OPEN_RETRIES). */
        private const val RETRY_DELAY_MS = 200L

        /** ~30 s of stereo 16-bit PCM kept for the reappearance audio bridge. */
        private const val PCM_RING_MAX_BYTES = SAMPLE_RATE * BYTES_PER_FRAME * 30
    }

    /** Current volume multiplier applied to each audio chunk. */
    @Volatile
    var currentVolume: Double = 1.0

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

    /**
     * Accumulated stderr of the currently-attached ffmpeg audio process, reset per [drainStderr] call and
     * read once the pump loop ends, so an unexpected end can report why.
     */
    private val stderrBuf = StringBuilder()

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
     */
    fun start(
        proc: Process,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean,
        startGate: CountDownLatch? = null,
        onUnexpectedEnd: (String) -> Unit = {},
    ): Thread {
        preludeFrames = 0L
        liveGate = null
        exposeLiveClock = true
        pcmFlowing = false
        sessionEpoch++
        drainStderr(proc, stopFlag)
        return daemon({ run(proc, terminated, stopFlag, startGate, onUnexpectedEnd) }, "MediaPlayer-audio").also { it.start() }
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
        return daemon({ runBridge(prelude, terminated, stopFlag, onUnexpectedEnd) }, "MediaPlayer-audio-bridge").also { it.start() }
    }

    /** Supplies the live `FFmpeg` audio process to an in-flight bridge session (see [startBridge]). */
    fun provideLiveInput(proc: Process, stopFlag: AtomicBoolean) {
        drainStderr(proc, stopFlag)
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
        onUnexpectedEnd: (String) -> Unit,
    ) {
        var ln: SourceDataLine? = null
        var pumped = false
        try {
            proc.inputStream.use { input ->
                val fmt = pcmFormat()
                val info = DataLine.Info(SourceDataLine::class.java, fmt)
                if (!AudioSystem.isLineSupported(info)) {
                    logger.warn("$debugLabel PCM line not supported.")
                    return
                }
                ln = openLine(info, fmt) ?: run {
                    logger.warn("$debugLabel [audio] line failed to open.")
                    return
                }
                if (terminated.get() || stopFlag.get()) return
                logger.debug("$debugLabel [audio] line open, waiting for start gate...")
                if (!awaitStartGate(startGate, terminated, stopFlag)) {
                    logger.debug("$debugLabel [audio] aborted before start gate opened (terminated/stopped).")
                    return
                }
                ln.start()
                line = ln
                logger.debug("$debugLabel [audio] start gate passed, line started — audio is now playing.")
                pumpLive(input, ln, terminated, stopFlag)
                pumped = true
            }
        } catch (e: IOException) {
            if (MediaPlayer.DEBUG && !terminated.get() && !stopFlag.get()) {
                logger.warn("$debugLabel Read: ${e.message}.")
            }
        } catch (e: Exception) {
            if (!terminated.get() && !stopFlag.get()) {
                logger.warn("$debugLabel Pipeline: ${e.message}.")
            }
        } finally {
            ln?.let {
                // A newer session may already own the field (overlapped restart): only clear our own line.
                if (line === it) line = null
                runCatching { it.flush() }
                runCatching { it.stop() }
                runCatching { it.close() }
            }
        }
        if (pumped && !terminated.get() && !stopFlag.get()) {
            onUnexpectedEnd(stderrSnapshot())
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
        try {
            val fmt = pcmFormat()
            val info = DataLine.Info(SourceDataLine::class.java, fmt)
            if (!AudioSystem.isLineSupported(info)) {
                logger.warn("$debugLabel PCM line not supported (bridge).")
                return
            }
            ln = openLine(info, fmt) ?: run {
                logger.warn("$debugLabel [audio] bridge line failed to open.")
                return
            }
            if (terminated.get() || stopFlag.get()) return
            ln.start()
            line = ln
            val cachedSec = prelude.size / (SAMPLE_RATE * BYTES_PER_FRAME).toDouble()
            logger.debug("$debugLabel [audio] bridge line started; playing ${"%.2f".format(cachedSec)}s cached prelude.")
            // 1) Cached prelude — paced naturally by the line (not ring-cached: already-played audio).
            writePrelude(ln, prelude, terminated, stopFlag)
            // 2) Continue with the live PCM on the SAME line: sample-continuous, no flush, no second line.
            val proc = awaitLiveInput(terminated, stopFlag) ?: run {
                logger.debug("$debugLabel [audio] bridge ended before live input attached.")
                return
            }
            logger.debug("$debugLabel [audio] bridge handing off cached -> live on one line.")
            proc.inputStream.use { input -> pumpLive(input, ln, terminated, stopFlag) }
            pumped = true
        } catch (e: Exception) {
            if (!terminated.get() && !stopFlag.get()) {
                logger.warn("$debugLabel Bridge pipeline: ${e.message}.")
            }
        } finally {
            ln?.let {
                // A newer session may already own the field (overlapped restart): only clear our own line.
                if (line === it) line = null
                runCatching { it.flush() }
                runCatching { it.stop() }
                runCatching { it.close() }
            }
        }
        if (pumped && !terminated.get() && !stopFlag.get()) {
            onUnexpectedEnd(stderrSnapshot())
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
            MediaBufferEffects.applyVolumeS16LE(chunk, n, currentVolume)
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

    /** Reads live PCM from [input] and writes it to [ln], ring-caching each chunk for the next bridge. */
    private fun pumpLive(input: InputStream, ln: SourceDataLine, terminated: AtomicBoolean, stopFlag: AtomicBoolean) {
        val chunk = ByteArray(CHUNK_BYTES)
        var firstChunk = true
        var totalBytes = 0L
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
            MediaBufferEffects.applyVolumeS16LE(chunk, n, currentVolume)
            writeFully(ln, chunk, n, terminated, stopFlag)
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
     * Drains the audio process's stderr, logs interesting lines FFmpeg emits as they arrive (it runs at
     *  -loglevel error), and accumulates stderr into [stderrBuf] for [stderrSnapshot].
     */
    private fun drainStderr(proc: Process, stopFlag: AtomicBoolean) {
        synchronized(stderrBuf) { stderrBuf.setLength(0) }
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
                        synchronized(stderrBuf) { stderrBuf.append(line).append('\n') }
                    }
                }
            } catch (_: IOException) {
            }
        }, "MediaPlayer-astderr").start()
    }

    /** Snapshot of the current process's accumulated stderr (see [drainStderr]). */
    private fun stderrSnapshot(): String = synchronized(stderrBuf) { stderrBuf.toString() }
}
