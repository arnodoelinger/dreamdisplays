package com.dreamdisplays.player.managers

import com.dreamdisplays.player.util.daemon
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Diagnostic service that periodically logs decoded FPS, GPU-upload FPS, dropped frames,
 * and current playback position. Only intended to be started when `DEBUG` mode is enabled.
 */
internal class StatsReporter(
    private val debugLabel: String,
    private val intervalMs: Long = 2000L,

    /** Called every interval; must return and reset the three counters atomically. */
    private val pollCounters: () -> Snapshot,
    private val getPositionMs: () -> Long,
    private val isLive: () -> Boolean,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/StatsReporter")

    /** Raw counter values sampled at one reporting interval. */
    data class Snapshot(val samplesIn: Long, val framesToGpu: Long, val framesDropped: Long)

    @Volatile private var executor: ScheduledExecutorService? = null

    /** Starts the periodic reporting task. No-op if already running. */
    fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { daemon(it, "MediaPlayer-stats") }.also { exec ->
            exec.scheduleAtFixedRate(::report, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Stops the reporting task. */
    fun stop() { executor?.shutdownNow(); executor = null }

    /** Samples the current frame counters, formats a one-line stats string, and logs it. */
    private fun report() {
        runCatching {
            val (inN, outN, dropN) = pollCounters()
            val sec = intervalMs / 1000.0
            logger.debug("$debugLabel decode=%.1ffps gpu=%.1ffps dropped=%.1f/s pos=%dms live=%s"
                .format(inN / sec, outN / sec, dropN / sec, getPositionMs(), isLive()))
        }
    }
}
