package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.api.media.audio.AudioDspStage
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.util.MediaBufferEffects
import com.dreamdisplays.media.player.util.MediaUtil
import com.dreamdisplays.media.player.util.daemon
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.*

/**
 * Manages PCM pipeline for `FFmpeg` audio process (owns audio master clock).
 */
internal class AudioSink(private val debugLabel: String) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** The PCM sample rate used by every session: 44.1 kHz, stereo, signed 16-bit little-endian. */
        const val SAMPLE_RATE = 44100

        /** Stereo 16-bit PCM: 4 bytes per frame. One second is SAMPLE_RATE * BYTES_PER_FRAME bytes. */
        const val BYTES_PER_FRAME = 4

        private const val CHUNK_BYTES = SAMPLE_RATE * 2 * 2 / 20
        private const val LINE_BUFFER_BYTES = CHUNK_BYTES * 8
        private const val MIN_PACE_BYTES = CHUNK_BYTES
        private const val MAX_PACE_BYTES = CHUNK_BYTES * 6
        private const val PACE_STEP_BYTES = CHUNK_BYTES
        private const val PACE_RECOVER_CHUNKS = 400
        private const val OPEN_RETRIES = 3
        private const val RETRY_DELAY_MS = 200L
        private const val CATCHUP_MIN_NANOS = 40_000_000L
        private const val CATCHUP_MAX_PASSES = 4
        private const val MAX_RESYNC_NANOS = 5_000_000_000L

        /** Converts a count of stereo 16-bit PCM frames into nanos of content. */
        fun framesToNanos(frames: Long): Long = frames * 1_000_000_000L / SAMPLE_RATE

        /** Converts a byte count of stereo 16-bit PCM into nanos of content. */
        fun bytesToNanos(bytes: Long): Long = framesToNanos(bytes / BYTES_PER_FRAME)
    }

    /**
     * Sample of audio master clock (atomic read of position, session, and meaning).
     */
    class ClockSample(
        @JvmField val nanos: Long,
        @JvmField val epoch: Int,
        @JvmField val originKnown: Boolean,
    ) {
        companion object {
            /** No line clock available. */
            val NONE = ClockSample(-1L, 0, false)
        }
    }

    private class LineSession(
        @JvmField val epoch: Int,
        @JvmField val originKnown: Boolean,
        @Volatile @JvmField var contentStartNanos: Long,
    ) {

        @Volatile
        @JvmField
        var line: SourceDataLine? = null

        /** True once live PCM has actually been read from the process (see [AudioSink.sampleClock]). */
        @Volatile
        @JvmField
        var pcmFlowing = false
    }

    /**
     * Content catch-up spec for session starting during playback (discard leading PCM).
     */
    class CatchUp(val contentStartNanos: Long, val playbackClock: () -> Long)

    /** Current volume multiplier applied to each audio chunk. */
    @Volatile
    var currentVolume: Double = 1.0

    @Volatile
    private var dspStage: AudioDspStage? = null

    /** Installs (or clears) the acoustics DSP stage for this session; resets its state immediately. */
    fun setDspStage(stage: AudioDspStage?) {
        dspStage = stage
        stage?.reset()
    }

    private var paceTargetBytes = MIN_PACE_BYTES
    private var paceCleanChunks = 0
    private var paceStreaming = false
    private val sessionLock = Any()

    @Volatile
    private var current: LineSession? = null
    private var epochCounter = 0

    /**
     * Samples the audio master clock. See [ClockSample]; [ClockSample.NONE] while no line clock is
     * available, in which case callers fall back to the wall clock.
     */
    fun sampleClock(): ClockSample {
        val s = current ?: return ClockSample.NONE
        val ln = s.line ?: return ClockSample.NONE
        if (!s.pcmFlowing) return ClockSample.NONE
        return ClockSample(s.contentStartNanos + framesToNanos(ln.longFramePosition), s.epoch, s.originKnown)
    }

    @Volatile
    private var parked: AtomicBoolean? = null

    /** Installs the session park flag (see [parked]); set once by the owning session manager. */
    fun setParkFlag(flag: AtomicBoolean?) {
        parked = flag
    }

    private val pendingResyncNanos = AtomicLong(0)

    /** Asks pump to drop next nanos of audio to restore lip sync after line stall. */
    fun requestResync(nanos: Long) {
        if (nanos <= 0L) return
        pendingResyncNanos.accumulateAndGet(nanos.coerceAtMost(MAX_RESYNC_NANOS), ::maxOf)
    }

    private fun applyPendingResync(
        session: LineSession, input: InputStream, terminated: AtomicBoolean, stopFlag: AtomicBoolean,
    ) {
        val target = pendingResyncNanos.getAndSet(0L)
        if (target <= 0L || !owns(session)) return
        val scratch = ByteArray(CHUNK_BYTES)
        var toSkip = target * SAMPLE_RATE / 1_000_000_000L * BYTES_PER_FRAME
        var skipped = 0L
        while (toSkip > 0 && !terminated.get() && !stopFlag.get()) {
            val n = MediaUtil.readFull(input, scratch, minOf(CHUNK_BYTES.toLong(), toSkip).toInt())
            if (n <= 0) break // EOF mid-skip: the pump loop reports it on its next read
            skipped += n
            toSkip -= n
        }
        if (skipped <= 0L) return
        session.contentStartNanos += bytesToNanos(skipped)
        logger.debug("$debugLabel [audio] re-synced by skipping ${bytesToNanos(skipped) / 1_000_000} ms of PCM.")
    }

    private fun beginSession(contentStartNanos: Long, originKnown: Boolean): LineSession {
        val session = synchronized(sessionLock) {
            LineSession(++epochCounter, originKnown, contentStartNanos).also { current = it }
        }
        pendingResyncNanos.set(0L) // A skip owed by the previous line means nothing to a fresh one
        dspStage?.reset()
        return session
    }

    private fun publishLine(session: LineSession, ln: SourceDataLine): Boolean {
        synchronized(sessionLock) {
            if (current !== session) return false
            session.line = ln
            return true
        }
    }

    private fun owns(session: LineSession): Boolean = current === session

    private fun retireSession(session: LineSession, ln: SourceDataLine?) {
        synchronized(sessionLock) {
            if (session.line === ln) session.line = null
            if (current === session) current = null
        }
    }

    /**
     * Starts the audio reading / writing loop. session's master clock (see [ClockSample]). (live HLS), so
     * [contentStartNanos] is the anchor for the session's timeline.
     */
    fun start(
        proc: Process,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean,
        contentStartNanos: Long,
        originKnown: Boolean,
        startGate: CountDownLatch? = null,
        onUnexpectedEnd: (String) -> Unit = {},
        catchUp: CatchUp? = null,
    ): Thread {
        val session = beginSession(contentStartNanos, originKnown)
        val stderrBuf = drainStderr(proc, stopFlag)
        return daemon(
            { run(session, proc, terminated, stopFlag, startGate, stderrBuf, onUnexpectedEnd, catchUp) },
            "MediaPlayer-audio",
        ).also { it.start() }
    }

    /**
     * Prewarms a replacement line for a seamless in-place audio-track switch and, once it is primed, flips it in
     */
    fun startSwitch(
        proc: Process,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean,
        contentStartNanos: Long,
        originKnown: Boolean,
        catchUp: CatchUp?,
        shouldPromote: () -> Boolean = { true },
        onPromoted: () -> Unit,
        onAborted: () -> Unit = {},
        onUnexpectedEnd: (String) -> Unit = {},
    ): Thread {
        val stderrBuf = drainStderr(proc, stopFlag)
        return daemon(
            {
                runSwitch(
                    proc,
                    terminated,
                    stopFlag,
                    contentStartNanos,
                    originKnown,
                    catchUp,
                    shouldPromote,
                    onPromoted,
                    onAborted,
                    stderrBuf,
                    onUnexpectedEnd
                )
            },
            "MediaPlayer-audio-switch-line",
        ).also { it.start() }
    }

    private fun runSwitch(
        proc: Process, terminated: AtomicBoolean, stopFlag: AtomicBoolean, contentStartNanos: Long,
        originKnown: Boolean, catchUp: CatchUp?, shouldPromote: () -> Boolean, onPromoted: () -> Unit,
        onAborted: () -> Unit, stderrBuf: StringBuilder, onUnexpectedEnd: (String) -> Unit,
    ) {
        var newLine: SourceDataLine? = null
        var session: LineSession? = null
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
                val skippedNanos = catchUp?.let { skipCatchUp(input, it, terminated, stopFlag) } ?: 0L

                if (isInterrupted() || !primeSwitchLine(input, newLine, terminated, stopFlag)) return@use
                if (isInterrupted() || !shouldPromote()) return@use

                // The flip publishes the new session and its line together, so no clock reader can ever
                // see the new line paired with the old session's content origin (or vice versa).
                val old = current?.line
                old?.apply {
                    runCatching { stop() }
                    runCatching { flush() }
                }
                val s = beginSession(contentStartNanos + skippedNanos, originKnown)
                session = s
                publishLine(s, newLine)
                newLine.start()
                // Only now may the clock be read: the line is running and its primed buffer is the
                // content at contentStartNanos, so frame position 0 really is that position.
                s.pcmFlowing = true
                old?.let { runCatching { it.close() } }

                promoted = true
                logger.debug("$debugLabel [audio] switch line promoted — new track playing.")
                onPromoted()

                pumpLive(s, input, newLine, terminated, stopFlag)
                pumped = true
            }
        }.onFailure { e ->
            if (isInterrupted()) return@onFailure
            when (e) {
                is IOException -> if (MediaPlayer.DEBUG) logger.warn("$debugLabel Switch read: ${e.message}.")
                else -> logger.warn("$debugLabel Switch pipeline: ${e.message}.")
            }
        }

        newLine?.let { ln ->
            session?.let { retireSession(it, ln) }
            runCatching { ln.flush() }
            runCatching { ln.stop() }
            runCatching { ln.close() }
        }

        if (!promoted) onAborted()

        if (pumped && !isInterrupted()) {
            onUnexpectedEnd(synchronized(stderrBuf) { stderrBuf.toString() })
        }
    }

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

    /** Flushes and closes the audio line immediately, retiring the clock. Safe to call from any thread. */
    fun stop() {
        val s = synchronized(sessionLock) { current.also { current = null } } ?: return
        val ln = s.line ?: return
        s.line = null
        runCatching { ln.flush() }
        runCatching { ln.stop() }
        runCatching { ln.close() }
    }

    /** Pauses playback for a warm park without closing or flushing the queued PCM. */
    fun pauseForPark() {
        current?.line?.let { runCatching { it.stop() } }
    }

    /** Resumes a line paused by [pauseForPark]. */
    fun resumeFromPark() {
        current?.line?.let { runCatching { it.start() } }
    }

    private fun run(
        session: LineSession, proc: Process, terminated: AtomicBoolean, stopFlag: AtomicBoolean,
        startGate: CountDownLatch?, stderrBuf: StringBuilder, onUnexpectedEnd: (String) -> Unit,
        catchUp: CatchUp? = null,
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
                catchUp?.let { session.contentStartNanos += skipCatchUp(input, it, terminated, stopFlag) }
                if (terminated.get() || stopFlag.get()) return@runCatching
                if (!publishLine(session, ln)) {
                    logger.debug("$debugLabel [audio] session superseded before its line started; abandoning it.")
                    return@runCatching
                }
                ln.start()
                logger.debug("$debugLabel [audio] start gate passed, line started — audio is now playing.")
                pumpLive(session, input, ln, terminated, stopFlag)
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
            retireSession(session, it)
            runCatching { it.flush() }
            runCatching { it.stop() }
            runCatching { it.close() }
        }

        if (pumped && !terminated.get() && !stopFlag.get()) {
            onUnexpectedEnd(synchronized(stderrBuf) { stderrBuf.toString() })
        }
    }

    private fun pcmFormat() = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE.toFloat(), 16, 2, 4, SAMPLE_RATE.toFloat(), false,
    )

    private fun skipCatchUp(
        input: InputStream, catchUp: CatchUp, terminated: AtomicBoolean, stopFlag: AtomicBoolean,
    ): Long {
        val scratch = ByteArray(CHUNK_BYTES)
        var skippedBytes = 0L
        repeat(CATCHUP_MAX_PASSES) {
            val contentNow = catchUp.contentStartNanos + bytesToNanos(skippedBytes)
            val gapNanos = catchUp.playbackClock() - contentNow
            if (gapNanos < CATCHUP_MIN_NANOS) {
                if (skippedBytes > 0L) {
                    logger.debug(
                        "$debugLabel [audio] catch-up skipped ${bytesToNanos(skippedBytes) / 1_000_000} ms " +
                                "of PCM to join in sync."
                    )
                }
                return bytesToNanos(skippedBytes)
            }
            var toSkip = gapNanos * SAMPLE_RATE / 1_000_000_000L * BYTES_PER_FRAME
            while (toSkip > 0 && !terminated.get() && !stopFlag.get()) {
                val n = MediaUtil.readFull(input, scratch, minOf(CHUNK_BYTES.toLong(), toSkip).toInt())
                if (n <= 0) return bytesToNanos(skippedBytes) // EOF mid-skip: pump loop will report it
                skippedBytes += n
                toSkip -= n
            }
            if (terminated.get() || stopFlag.get()) return bytesToNanos(skippedBytes)
        }
        return bytesToNanos(skippedBytes)
    }

    private fun pumpLive(
        session: LineSession, input: InputStream, ln: SourceDataLine,
        terminated: AtomicBoolean, stopFlag: AtomicBoolean,
    ) {
        val chunk = ByteArray(CHUNK_BYTES)
        var firstChunk = true
        var totalBytes = 0L
        // Fresh line for this session: don't let its initially-empty buffer read as an underrun on the
        // first paced write. The learned target itself persists across sessions.
        paceStreaming = false
        paceCleanChunks = 0
        while (!terminated.get() && !stopFlag.get()) {
            if (parkIfRequested(ln, terminated, stopFlag)) break
            if (!firstChunk) applyPendingResync(session, input, terminated, stopFlag)
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
                session.pcmFlowing = true
            }
            val dsp = if (owns(session)) dspStage else null
            dsp?.process(chunk, n, currentVolume) ?: MediaBufferEffects.applyVolumeS16LE(chunk, n, currentVolume)
            paceLiveWrite(ln, chunk, n, terminated, stopFlag)
        }
    }

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
            val surplus = (ln.bufferSize - ln.available()).coerceAtLeast(0) - paceTargetBytes
            if (surplus <= 0) break
            val drainMs = (surplus.toLong() * 1_000L / (SAMPLE_RATE * BYTES_PER_FRAME)).coerceIn(1L, 20L)
            try {
                Thread.sleep(drainMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); break
            }
        }
    }

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
                        synchronized(buf) { MediaUtil.appendCapped(buf, line) }
                    }
                }
            } catch (_: IOException) {
            }
        }, "MediaPlayer-astderr").start()
        return buf
    }
}
