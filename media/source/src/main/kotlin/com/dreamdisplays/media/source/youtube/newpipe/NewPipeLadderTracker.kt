package com.dreamdisplays.media.source.youtube.newpipe

import kotlinx.atomicfu.atomic

/**
 * How often recent YouTube resolutions came back with a real adaptive ladder vs. walled with only a
 * single muxed 360p stream. YouTube's PO-token / SABR wall comes and goes, and which side of it a
 * client is on decides whether the `yt-dlp` fallback is going to be needed at all — so it is
 * measured rather than assumed.
 */
object NewPipeLadderTracker {
    private const val MIN_LADDER_SAMPLES = 4
    private const val OVERLAP_MISS_PERCENT = 34
    private const val LADDER_DECAY_AT = 64
    private val ladderHits = atomic(0)
    private val ladderMisses = atomic(0)

    /** Whether to start `yt-dlp` fallback alongside extraction instead of after. */
    fun shouldOverlapFallback(): Boolean {
        val hits = ladderHits.value
        val misses = ladderMisses.value
        val samples = hits + misses
        return samples < MIN_LADDER_SAMPLES || misses * 100 >= samples * OVERLAP_MISS_PERCENT
    }

    /** Records whether a completed extraction produced a full ladder, decaying the older history. */
    fun recordLadderOutcome(laddered: Boolean) {
        if (laddered) ladderHits.incrementAndGet() else ladderMisses.incrementAndGet()
        val hits = ladderHits.value
        val misses = ladderMisses.value
        if (hits + misses < LADDER_DECAY_AT) return
        ladderHits.value = hits / 2
        ladderMisses.value = misses / 2
    }
}
