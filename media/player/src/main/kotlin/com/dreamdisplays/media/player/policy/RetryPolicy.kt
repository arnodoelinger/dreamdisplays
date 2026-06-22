package com.dreamdisplays.media.player.policy

import com.dreamdisplays.media.player.util.MediaUtil

/**
 * Encapsulates retry logic for stream failures: decides whether to retry, whether to invalidate
 * the `yt-dlp` URL cache, and provides the exponential back-off delay for the next attempt.
 */
internal class RetryPolicy(private val maxRetries: Int = 3) {
    private val backoffMs = longArrayOf(1000, 3000, 8000)

    /** Number of retry attempts made so far. */
    var retries = 0
        private set

    /** True when all retries are exhausted. */
    val exhausted: Boolean get() = retries >= maxRetries

    /**
     * Analyses [stderr] and [normalEos] to decide whether and how to retry.
     * Returns `null` when retries are exhausted or the error is unrecoverable.
     *
     * @param isLive whether the current stream is a live stream
     */
    fun evaluate(stderr: String, normalEos: Boolean, isLive: Boolean): Decision? {
        if (exhausted) return null
        val is403or404 = "403" in stderr || "Forbidden" in stderr || "404" in stderr || "Not Found" in stderr
        if (is403or404 || MediaUtil.isTransientError(stderr)) return Decision(invalidateCache = is403or404)
        if (normalEos && isLive) return Decision(invalidateCache = true)
        return null
    }

    /**
     * Returns the back-off delay in milliseconds for the next attempt and increments [retries].
     * Call this immediately before scheduling the retry.
     */
    fun nextDelay(): Long = backoffMs[retries.coerceAtMost(backoffMs.lastIndex)].also { retries++ }

    /** Resets [retries] to 0 after a successful stream start. */
    fun reset() {
        retries = 0
    }

    /** Describes how a retry should be performed. */
    data class Decision(
        /** If `true`, the `yt-dlp` cache must be purged before re-fetching stream URLs. */
        val invalidateCache: Boolean,
    )
}
