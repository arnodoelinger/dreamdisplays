package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.preparation.PreparedMedia
import com.dreamdisplays.platform.client.managers.WarmParkPolicy
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** In-memory LRU of native video replay snapshots retained across soft display unloads. */
internal object DisplayReplayCache {
    private const val DEFAULT_TTL_MS = 5L * 60L * 1000L
    private const val DEFAULT_MAX_BYTES = 512L * 1024L * 1024L
    private const val POSITION_TOLERANCE_NS = 1_000_000_000L

    private class Entry(val url: String, val positionNanos: Long, val snapshot: ByteArray, val prepared: PreparedMedia?)

    private val maxBytes: Long = WarmParkPolicy.replayCacheBudgetBytes.coerceAtMost(DEFAULT_MAX_BYTES)

    private val entries: Cache<UUID, Entry> = Caffeine.newBuilder()
        .maximumWeight(maxBytes)
        .weigher { _: UUID, entry: Entry -> entry.snapshot.size }
        .expireAfterWrite(DEFAULT_TTL_MS, TimeUnit.MILLISECONDS)
        .build()

    /** Stores [snapshot] (plus optional resolved [prepared] streams) for [uuid]. */
    fun put(uuid: UUID, url: String, positionNanos: Long, snapshot: ByteArray, prepared: PreparedMedia?) {
        if (snapshot.isEmpty() || maxBytes <= 0L) return
        entries.put(uuid, Entry(url, positionNanos, snapshot, prepared))
        entries.cleanUp()
    }

    /** Returns and removes a matching one-shot replay bootstrap for [uuid], if still fresh. */
    fun take(uuid: UUID, url: String, positionNanos: Long): MediaPlayer.ReplayBootstrap? {
        val entry = entries.getIfPresent(uuid) ?: return null
        entries.invalidate(uuid)
        if (entry.url != url || abs(entry.positionNanos - positionNanos) > POSITION_TOLERANCE_NS) {
            return null
        }
        return MediaPlayer.ReplayBootstrap(entry.snapshot, entry.positionNanos)
            .also { it.prepared = entry.prepared }
    }

    /** Drops any retained snapshot for [uuid]. */
    fun remove(uuid: UUID) {
        entries.invalidate(uuid)
    }

    /** Drops every retained replay snapshot. */
    fun clear() {
        entries.invalidateAll()
    }
}
