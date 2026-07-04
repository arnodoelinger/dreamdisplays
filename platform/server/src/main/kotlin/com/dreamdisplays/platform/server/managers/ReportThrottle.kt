package com.dreamdisplays.platform.server.managers

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Per-display and per-reporter cooldown tracking for [DisplayManager]'s webhook reports. The
 * per-reporter limit stops an attacker from amplifying the webhook by spreading reports across
 * many displays.
 */
internal class ReportThrottle {
    /** Cooldown marker for a report, with the duration in nanoseconds. */
    private data class ReportCooldown(val durationNanos: Long)

    /**
     * Expiry policy for the report cooldown cache. The cooldown is set on creation and update, and
     * read operations do not reset it.
     */
    private val expiry = object : Expiry<UUID, ReportCooldown> {
        override fun expireAfterCreate(key: UUID, value: ReportCooldown, currentTime: Long): Long =
            value.durationNanos

        override fun expireAfterUpdate(
            key: UUID,
            value: ReportCooldown,
            currentTime: Long,
            currentDuration: Long
        ): Long = value.durationNanos

        override fun expireAfterRead(
            key: UUID,
            value: ReportCooldown,
            currentTime: Long,
            currentDuration: Long
        ): Long = currentDuration
    }

    /** Report cooldowns per display id. */
    private val reportTime: Cache<UUID, ReportCooldown> = Caffeine.newBuilder()
        .maximumSize(REPORT_RATE_LIMIT_MAX_SIZE)
        .expireAfter(expiry)
        .build()

    /** Report cooldowns per reporter id. */
    private val reporterTime: Cache<UUID, ReportCooldown> = Caffeine.newBuilder()
        .maximumSize(REPORT_RATE_LIMIT_MAX_SIZE)
        .expireAfter(expiry)
        .build()

    /** Lock for synchronizing access to the caches. */
    private val lock = Any()

    /**
     * Checks whether a report from [reporterId] about display [id] should be rate-limited. Drops
     * the request when either the per-display or the per-reporter cooldown is still active.
     * Records both cooldown markers only when the report may proceed.
     */
    fun isThrottled(id: UUID, reporterId: UUID, cooldownMs: Long): Boolean {
        val durationNanos = TimeUnit.MILLISECONDS.toNanos(cooldownMs).coerceAtLeast(0L)
        if (durationNanos == 0L) return false

        synchronized(lock) {
            if (reportTime.getIfPresent(id) != null || reporterTime.getIfPresent(reporterId) != null) {
                return true
            }
            val marker = ReportCooldown(durationNanos)
            reportTime.put(id, marker)
            reporterTime.put(reporterId, marker)
            return false
        }
    }

    private companion object {
        /** Maximum number of entries in the report cooldown caches. */
        const val REPORT_RATE_LIMIT_MAX_SIZE = 20_000L
    }
}
