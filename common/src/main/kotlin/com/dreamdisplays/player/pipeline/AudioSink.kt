package com.dreamdisplays.player.pipeline

import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.player.util.MediaBufferEffects
import com.dreamdisplays.player.util.MediaUtil
import com.dreamdisplays.player.util.daemon
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*

/** Manages the `javax.sound` PCM pipeline for one `FFmpeg` audio process. */
internal class AudioSink(private val debugLabel: String) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/AudioSink")
    companion object {
        const val SAMPLE_RATE = 44100
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 * 2 / 20
        private const val LINE_BUFFER_BYTES = CHUNK_BYTES * 10
        private const val OPEN_RETRIES = 3
        private const val RETRY_DELAY_MS = 200L
    }

    /** Current volume multiplier applied to each audio chunk. */
    @Volatile var currentVolume: Double = 1.0

    /**
     * Frame position of the open audio line, or -1 when no line is active.
     * Used by [PlaybackClock.audioClockNanos] for A / V sync.
     */
    val framePosition: Long get() = line?.longFramePosition ?: -1L

    @Volatile private var line: SourceDataLine? = null

    /** Starts the audio reading / writing loop. */
    fun start(proc: Process, terminated: AtomicBoolean, stopFlag: AtomicBoolean): Thread {
        drainStderr(proc)
        return daemon({ run(proc, terminated, stopFlag) }, "MediaPlayer-audio").also { it.start() }
    }

    /** Flushes and closes the audio line immediately. Safe to call from any thread. */
    fun stop() {
        val ln = line ?: return
        line = null
        runCatching { ln.flush() }
        runCatching { ln.stop() }
        runCatching { ln.close() }
    }

    /**
     * Runs the audio reading / writing loop until the process ends or [terminated] / [stopFlag] is set.
     */
    private fun run(proc: Process, terminated: AtomicBoolean, stopFlag: AtomicBoolean) {
        var ln: SourceDataLine? = null
        try {
            proc.inputStream.use { input ->
                val fmt = AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    SAMPLE_RATE.toFloat(), 16, 2, 4, SAMPLE_RATE.toFloat(), false,
                )
                val info = DataLine.Info(SourceDataLine::class.java, fmt)
                if (!AudioSystem.isLineSupported(info)) {
                    logger.warn("$debugLabel PCM line not supported.")
                    return
                }
                ln = openLine(info, fmt) ?: return
                if (terminated.get() || stopFlag.get()) return
                ln.start()
                line = ln

                val chunk = ByteArray(CHUNK_BYTES)
                while (!terminated.get() && !stopFlag.get()) {
                    val n = MediaUtil.readFull(input, chunk, CHUNK_BYTES)
                    if (n <= 0) break
                    MediaBufferEffects.applyVolumeS16LE(chunk, n, currentVolume)
                    var written = 0
                    while (written < n && !terminated.get() && !stopFlag.get()) {
                        val w = ln.write(chunk, written, n - written)
                        if (w <= 0) break
                        written += w
                    }
                }
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

    /** Drains the process's stderr stream to /dev/null. */
    private fun drainStderr(proc: Process) = daemon({
        try { proc.errorStream.transferTo(OutputStream.nullOutputStream()) } catch (_: IOException) {}
    }, "MediaPlayer-astderr").start()
}
