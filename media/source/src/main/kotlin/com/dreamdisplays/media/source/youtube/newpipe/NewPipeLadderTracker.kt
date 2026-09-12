package com.dreamdisplays.media.source.youtube.newpipe

import kotlinx.atomicfu.atomic

/**
 * How often recent YouTube resolutions came back with a real adaptive ladder vs. walled with only a
 * single muxed 360p stream. YouTube's PO-token / SABR wall comes and goes, and which side of it a
 * client is on decides whether the `yt-dlp` fallback is going to be needed at all — so it is
 * measured rather than assumed.
 */
object NewPipeLadderTracker {
    /** Kill switch for the overlapped fallback (see [shouldOverlapFallback]). */
    private val OVERLAP_FALLBACK_ENABLED: Boolean =
        System.getProperty("dreamdisplays.resolve.overlapFallback", "true").toBoolean()

    /** Resolutions observed before the ladder rate is trusted enough to stop speculating. */
    private const val MIN_LADDER_SAMPLES = 4

    /** Walled share of recent resolutions at which the `yt-dlp` fallback is worth starting early. */
    private const val OVERLAP_MISS_PERCENT = 34

    /** Halve the counters past this many samples, so the rate tracks YouTube's current behavior. */
    private const val LADDER_DECAY_AT = 64

    /** How often recent YouTube resolutions came back with a real adaptive ladder. */
    private val ladderHits = atomic(0)

    /** How often recent YouTube resolutions came back walled, with only a single muxed 360p stream. */
    private val ladderMisses = atomic(0)

    /** Whether to start `yt-dlp` fallback alongside extraction instead of after. */
    fun shouldOverlapFallback(): Boolean {
        if (!OVERLAP_FALLBACK_ENABLED) return false
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
