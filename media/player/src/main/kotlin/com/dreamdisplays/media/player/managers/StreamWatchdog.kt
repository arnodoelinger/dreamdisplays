package com.dreamdisplays.media.player.managers

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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

    @Volatile
    private var executor: ScheduledExecutorService? = null

    /** Start watchdog. */
    fun start() {
        stop()
        executor = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "MediaPlayer-watchdog").apply { isDaemon = true }
        }.also {
            it.scheduleAtFixedRate(::check, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Stop watchdog. */
    fun stop() {
        executor?.shutdownNow()
        executor = null
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
