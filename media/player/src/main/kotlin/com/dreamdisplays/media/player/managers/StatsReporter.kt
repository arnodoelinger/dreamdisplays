package com.dreamdisplays.media.player.managers

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

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
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/StatsReporter")

    /** Raw counter values sampled at one reporting interval. */
    data class Snapshot(val samplesIn: Long, val framesToGpu: Long, val framesDropped: Long)

    /** Periodic reporting task. */
    @Volatile
    private var job: Job? = null

    /** Coroutine scope for the reporting task. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("MediaPlayer-stats"))

    /** Starts the periodic reporting task. No-op if already running. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(intervalMs)
            while (isActive) {
                report()
                delay(intervalMs)
            }
        }
    }

    /** Stops the reporting task. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Samples the current frame counters, formats a one-line stats string, and logs it. */
    private fun report() {
        runCatching {
            val (inN, outN, dropN) = pollCounters()
            val sec = intervalMs / 1000.0
            logger.debug(
                "$debugLabel decode=%.1ffps gpu=%.1ffps dropped=%.1f/s pos=%dms live=%s"
                    .format(inN / sec, outN / sec, dropN / sec, getPositionMs(), isLive())
            )
        }
    }
}
