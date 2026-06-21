package com.dreamdisplays.player.pipeline

import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.player.util.MediaBufferEffects
import com.dreamdisplays.player.util.MediaUtil
import com.dreamdisplays.player.util.daemon
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*

/** Manages the `javax.sound` PCM pipeline for one `FFmpeg` audio process. */
internal class AudioSink(private val debugLabel: String) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/AudioSink")
    companion object {
        const val SAMPLE_RATE = 44100

        /** Stereo 16-bit PCM: 4 bytes per frame. One second is SAMPLE_RATE * BYTES_PER_FRAME bytes. */
        const val BYTES_PER_FRAME = 4
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 * 2 / 20
        private const val LINE_BUFFER_BYTES = CHUNK_BYTES * 10
        private const val OPEN_RETRIES = 3
        private const val RETRY_DELAY_MS = 200L

        /** ~30 s of stereo 16-bit PCM kept for the reappearance audio bridge. */
        private const val PCM_RING_MAX_BYTES = SAMPLE_RATE * BYTES_PER_FRAME * 30
    }

    /** Current volume multiplier applied to each audio chunk. */
    @Volatile var currentVolume: Double = 1.0

    /**
     * Live-relative frame position of the open audio line, or -1 when no line is active (or the line is
     * still playing a bridge [preludeFrames] window). Used by [PlaybackClock.audioClockNanos] for A / V
     * sync. The cached prelude played ahead of the live PCM on a bridge line is subtracted so the clock
     * mapping (anchored at the live edge by `rebaseTo`) stays continuous across the cached -> live seam, and
     * video pacing keeps using the wall clock while the prelude is still playing.
     */
    val framePosition: Long
        get() {
            val ln = line ?: return -1L
            if (!exposeLiveClock) return -1L
            val live = ln.longFramePosition - preludeFrames
            return if (live < 0L) -1L else live
        }

    @Volatile private var line: SourceDataLine? = null

    /** Frames of cached prelude played ahead of the live PCM on a bridge line (0 for a normal session). */
    @Volatile private var preludeFrames = 0L

    /**
     * Whether [framePosition] reports the line clock. False during a bridge's cached-prelude phase, so the
     * audio clock is hidden (it would still read the pre-handoff seek offset, stalling video pacing) until
     * [onBridgeHandoff] re-anchors the playback clock to the live edge. Always true for a normal session.
     */
    @Volatile private var exposeLiveClock = true

    /** Gate a bridge session waits on for its live `FFmpeg` process; null outside a bridge. */
    @Volatile private var liveGate: CountDownLatch? = null
    @Volatile private var liveProc: Process? = null

    /** When set and true, the reader pauses the line (keeping it open) and idles — used to keep the audio
     *  process warm while a display is parked out of render distance. Set once per session; persists across
     *  every audio path (normal start and the reappearance bridge) so any live audio thread observes it. */
    @Volatile private var parked: AtomicBoolean? = null

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
            val end = (pcmRingBytes - unplayed).coerceAtLeast(0)
            val take = end.coerceAtMost(maxBytes)
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

    /** Starts the audio reading / writing loop. */
    fun start(
        proc: Process,
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean,
        startGate: CountDownLatch? = null,
    ): Thread {
        preludeFrames = 0L
        liveGate = null
        exposeLiveClock = true
        drainStderr(proc)
        return daemon({ run(proc, terminated, stopFlag, startGate) }, "MediaPlayer-audio").also { it.start() }
    }

    /**
     * Starts a reappearance bridge on a single line: plays the cached [prelude] immediately, then — on the
     * same [SourceDataLine], with no flush and no second line — continues with the live PCM once
     * [provideLiveInput] supplies its process. The seam is therefore sample-continuous. [framePosition]
     * stays -1 until the line crosses out of the prelude, so video pacing is unaffected while it plays.
     */
    fun startBridge(prelude: ByteArray, terminated: AtomicBoolean, stopFlag: AtomicBoolean): Thread {
        preludeFrames = (prelude.size / BYTES_PER_FRAME).toLong()
        liveProc = null
        liveGate = CountDownLatch(1)
        exposeLiveClock = false
        return daemon({ runBridge(prelude, terminated, stopFlag) }, "MediaPlayer-audio-bridge").also { it.start() }
    }

    /** Supplies the live `FFmpeg` audio process to an in-flight bridge session (see [startBridge]). */
    fun provideLiveInput(proc: Process) {
        drainStderr(proc)
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

    /**
     * Runs the audio reading / writing loop until the process ends or [terminated] / [stopFlag] is set.
     */
    private fun run(proc: Process, terminated: AtomicBoolean, stopFlag: AtomicBoolean, startGate: CountDownLatch?) {
        var ln: SourceDataLine? = null
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
                line = null
                runCatching { it.flush() }
                runCatching { it.stop() }
                runCatching { it.close() }
            }
        }
    }

    /**
     * Runs a reappearance bridge on one line (see [startBridge]): opens the line, plays the cached
     * [prelude], then continues with the live PCM on the same line once it is attached. The prelude is
     * not ring-cached (it is already-played audio); only the live PCM feeds the rolling ring.
     */
    private fun runBridge(prelude: ByteArray, terminated: AtomicBoolean, stopFlag: AtomicBoolean) {
        var ln: SourceDataLine? = null
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
        } catch (e: Exception) {
            if (!terminated.get() && !stopFlag.get()) {
                logger.warn("$debugLabel Bridge pipeline: ${e.message}.")
            }
        } finally {
            ln?.let {
                line = null
                runCatching { it.flush() }
                runCatching { it.stop() }
                runCatching { it.close() }
            }
        }
    }

    /** The PCM line format shared by every session: 44.1 kHz stereo signed 16-bit little-endian. */
    private fun pcmFormat() = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE.toFloat(), 16, 2, 4, SAMPLE_RATE.toFloat(), false,
    )

    /** Writes the cached bridge prelude to [ln] (volume applied), without ring-caching it. */
    private fun writePrelude(ln: SourceDataLine, prelude: ByteArray, terminated: AtomicBoolean, stopFlag: AtomicBoolean) {
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
                if (firstChunk) {
                    logger.warn("$debugLabel [audio] no PCM data from ffmpeg (EOF on first read).")
                } else if (!terminated.get() && !stopFlag.get()) {
                    val seconds = totalBytes / (SAMPLE_RATE * BYTES_PER_FRAME).toDouble()
                    logger.warn("$debugLabel [audio] PCM stream ended after ${"%.1f".format(seconds)}s.")
                }
                break
            }
            totalBytes += n
            if (firstChunk) { logger.debug("$debugLabel [audio] first PCM chunk received ($n bytes)."); firstChunk = false }
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
        runCatching { ln.stop() } // pause playback, keep the buffered tail and the line open
        while (pk.get() && !terminated.get() && !stopFlag.get()) {
            try { Thread.sleep(20) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return true }
        }
        if (terminated.get() || stopFlag.get()) return true
        runCatching { ln.start() } // resume from exactly where it paused (frame position is continuous)
        return false
    }

    /** Writes the first [n] bytes of [chunk] to [ln], retrying short writes until stop/terminate. */
    private fun writeFully(ln: SourceDataLine, chunk: ByteArray, n: Int, terminated: AtomicBoolean, stopFlag: AtomicBoolean) {
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

    /** Drains the audio process's stderr and logs each line FFmpeg emits as it arrives (it runs at -loglevel error). */
    private fun drainStderr(proc: Process) = daemon({
        try {
            proc.errorStream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) logger.warn("$debugLabel [audio] FFmpeg stderr: ${line.trim()}.")
            }
        } catch (_: IOException) {}
    }, "MediaPlayer-astderr").start()
}
