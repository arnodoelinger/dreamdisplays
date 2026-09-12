package com.dreamdisplays.media.player.managers

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Watches whether frames are arriving. If no frame arrives within the applicable threshold, calls [onStall] and stops itself;
 * the caller decides how to recover.
 */
internal class StreamWatchdog(
    private val debugLabel: String,
    private val isSessionActive: () -> Boolean,
    private val getLastFrameNanos: () -> Long,
    private val stallThresholdMs: Long = 45_000L,
    private val startupThresholdMs: Long = 20_000L,
    private val checkIntervalMs: Long = 1_000L,
    private val onStall: () -> Unit,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var job: Job? = null

    private var deliveredAFrame = false
    private var lastSeenStamp = 0L
    private var silentSinceNanos = 0L

    /** Coroutine scope for the watchdog task. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("MediaPlayer-watchdog"))

    /** Start watchdog. */
    fun start() {
        stop()
        deliveredAFrame = false
        lastSeenStamp = getLastFrameNanos()
        silentSinceNanos = System.nanoTime()
        job = scope.launch {
            delay(checkIntervalMs.milliseconds)
            // Stops at the first stall: recovery is the caller's job, and it restarts the watchdog
            while (isActive && check()) {
                delay(checkIntervalMs.milliseconds)
            }
        }
    }

    /** Stop watchdog. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * One pass over [stallThresholdMs] / [startupThresholdMs]. Returns false once [onStall] has been called, which ends
     * the watchdog's polling loop.
     */
    private fun check(): Boolean {
        return runCatching {
            if (!isSessionActive()) {
                silentSinceNanos = System.nanoTime()
                return true
            }
            val stamp = getLastFrameNanos()
            if (stamp != lastSeenStamp) {
                lastSeenStamp = stamp
                deliveredAFrame = true
                silentSinceNanos = stamp
            }
            val silenceMs = (System.nanoTime() - silentSinceNanos) / 1_000_000L
            if (silenceMs < (if (deliveredAFrame) stallThresholdMs else startupThresholdMs)) return true
            val what = if (deliveredAFrame) "No frames for $silenceMs ms" else "No first frame after $silenceMs ms"
            logger.warn("$debugLabel $what. Restarting...")
            onStall()
            false
        }.getOrDefault(true)
    }
}
