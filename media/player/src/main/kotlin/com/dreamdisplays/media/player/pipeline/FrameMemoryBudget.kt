package com.dreamdisplays.media.player.pipeline

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide cap on the memory decoded frames and encoded-packet caches may hold, shared by every live
 * playback.
 */
internal object FrameMemoryBudget {
    private const val MIB = 1024L * 1024L
    private const val HEAP_SHARE = 0.3
    private const val MIN_BUDGET_BYTES = 384L * MIB
    private const val MAX_BUDGET_BYTES = 2048L * MIB
    private const val PACKET_CACHE_TOTAL_BYTES = 512L * MIB
    private const val MIN_PACKET_CACHE_BYTES = 16L * MIB

    private val frameBudgetBytes: Long =
        (Runtime.getRuntime().maxMemory() * HEAP_SHARE).toLong().coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)

    private val livePlaybacks = AtomicInteger()

    /** Counts a newly started prebuffer; pair with [release] when its consumer exits. */
    fun acquire() {
        livePlaybacks.incrementAndGet()
    }

    /** Uncounts a prebuffer whose consumer has exited. */
    fun release() {
        livePlaybacks.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /**
     * How many frames of [frameBytes] one more playback may keep in flight, at most [wanted]: the budget is split
     * evenly between the live playbacks plus this one, minus [outsideQueue] frames that live outside its queue.
     */
    fun framesFor(frameBytes: Int, wanted: Int, floor: Int, outsideQueue: Int): Int {
        if (frameBytes <= 0) return wanted
        val share = frameBudgetBytes / (livePlaybacks.get() + 1)
        val frames = (share / frameBytes - outsideQueue).toInt()
        return frames.coerceIn(floor.coerceAtMost(wanted), wanted)
    }

    /** Byte cap for one more encoded-packet cache, at most [wanted]. */
    fun packetCacheBytesFor(wanted: Long): Long {
        if (wanted <= 0) return wanted
        val share = PACKET_CACHE_TOTAL_BYTES / (livePlaybacks.get() + 1).coerceAtLeast(1)
        return share.coerceIn(MIN_PACKET_CACHE_BYTES.coerceAtMost(wanted), wanted)
    }
}
