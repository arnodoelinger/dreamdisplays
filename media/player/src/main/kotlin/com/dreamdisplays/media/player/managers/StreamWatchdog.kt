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
 * Watches whether frames are arriving. If no frame arrives within [stallThresholdMs],
 * calls [onStall] and resets.
 */
internal class StreamWatchdog(
    private val debugLabel: String,
    private val isActive: () -> Boolean,
    private val getLastFrameNanos: () -> Long,
    private val stallThresholdMs: Long = 30_000L,
    private val checkIntervalMs: Long = 1_000L,
    private val onStall: () -> Unit,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/StreamWatchdog")

    /** Watchdog task. */
    @Volatile
    private var job: Job? = null

    /** Coroutine scope for the watchdog task. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("MediaPlayer-watchdog"))

    /** Start watchdog. */
    fun start() {
        stop()
        job = scope.launch {
            delay(checkIntervalMs)
            while (isActive) {
                check()
                delay(checkIntervalMs)
            }
        }
    }

    /** Stop watchdog. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Main checker for [stallThresholdMs]. */
    private fun check() {
        runCatching {
            if (!isActive()) return
            val silenceMs = (System.nanoTime() - getLastFrameNanos()) / 1_000_000L
            if (silenceMs >= stallThresholdMs) {
                logger.warn("$debugLabel No frames for ${silenceMs} ms. Restarting...")
                onStall()
            }
        }
    }
}
