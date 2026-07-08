package com.dreamdisplays.platform.server.managers

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit

/**
 * Generic per-key cooldown gate for client-triggered actions that are cheap individually but costly
 * in aggregate — a disk write, a broadcast to every viewer, or work pushed onto every other client
 * (e.g. re-resolving a new video URL).
 *
 * A spamming client can send packets far faster than any legitimate user interaction would, so gating
 * admission by key bounds the amplification independent of any receiver-facing rebroadcast throttle
 * (see [StateManager]'s sync rebroadcast rate limit, which only limits fan-out, not how often the sender's
 * action itself may run).
 */
internal class ActionThrottle(maxEntries: Long = 20_000L) {
    /** Last-acted timestamp (ms) per key. */
    private val lastActionMs: Cache<Any, Long> = Caffeine.newBuilder()
        .maximumSize(maxEntries)
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build()

    /**
     * Returns true (and records now as [key]'s last action) if [key] may proceed; false when it
     * acted within the last [cooldownMs]. Atomic per key, so concurrent callers can't both pass.
     */
    fun tryAcquire(key: Any, cooldownMs: Long): Boolean {
        if (cooldownMs <= 0L) return true
        val now = System.currentTimeMillis()
        var acquired = false
        lastActionMs.asMap().compute(key) { _, last ->
            if (last == null || now - last >= cooldownMs) {
                acquired = true
                now
            } else {
                last
            }
        }
        return acquired
    }
}
