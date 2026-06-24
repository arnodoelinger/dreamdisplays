package com.dreamdisplays.platform.client.displays

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.preparation.PreparedMedia
import com.dreamdisplays.platform.client.managers.WarmParkPolicy
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** In-memory LRU of native video replay snapshots retained across soft display unloads. */
internal object DisplayReplayCache {
    /** Default TTL for replay snapshots in milliseconds. */
    private const val DEFAULT_TTL_MS = 5L * 60L * 1000L

    /** Default JVM-side memory cap for retained replay snapshots. */
    private const val DEFAULT_MAX_BYTES = 512L * 1024L * 1024L

    /** Maximum allowed difference between requested and actual replay position. */
    private const val POSITION_TOLERANCE_NS = 1_000_000_000L

    /** One retained replay snapshot: the encoded video bytes, optional audio PCM, and resolved streams. */
    private data class Entry(
        val url: String,
        val positionNanos: Long,
        val snapshot: ByteArray,
        val audioPcm: ByteArray?,
        val prepared: PreparedMedia?,
    )

    /** Snapshot time-to-live in milliseconds. */
    private val ttlMs: Long =
        System.getProperty("dreamdisplays.cache.ttlMs")?.toLongOrNull()?.coerceAtLeast(0L) ?: DEFAULT_TTL_MS

    /** Total JVM-side memory cap for retained replay snapshots. */
    private val maxBytes: Long =
        System.getProperty("dreamdisplays.cache.jvmMaxBytes")?.toLongOrNull()?.coerceAtLeast(0L)
            ?: WarmParkPolicy.replayCacheBudgetBytes.coerceAtMost(DEFAULT_MAX_BYTES)

    private val cacheEnabled = ttlMs > 0L && maxBytes > 0L

    /** Snapshots by UUID; TTL and byte-weighted eviction. */
    private val entries: Cache<UUID, Entry> = Caffeine.newBuilder()
        .maximumWeight(if (cacheEnabled) maxBytes else 0L)
        .weigher { _: UUID, entry: Entry -> entryBytes(entry).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        .expireAfterWrite(ttlMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        .build()

    /** Stores [snapshot] (plus optional cached [audioPcm] / resolved [prepared] streams) for [uuid]. */
    fun put(
        uuid: UUID,
        url: String,
        positionNanos: Long,
        snapshot: ByteArray,
        audioPcm: ByteArray?,
        prepared: PreparedMedia?
    ) {
        if (snapshot.isEmpty() || !cacheEnabled) return
        entries.put(uuid, Entry(url, positionNanos, snapshot, audioPcm, prepared))
        entries.cleanUp()
    }

    /** Returns and removes a matching one-shot replay bootstrap for [uuid], if still fresh. */
    fun take(uuid: UUID, url: String, positionNanos: Long): MediaPlayer.ReplayBootstrap? {
        val entry = entries.getIfPresent(uuid) ?: return null
        entries.invalidate(uuid)
        if (entry.url != url || abs(entry.positionNanos - positionNanos) > POSITION_TOLERANCE_NS) {
            return null
        }
        return MediaPlayer.ReplayBootstrap(entry.snapshot, entry.positionNanos, entry.audioPcm)
            .also { it.prepared = entry.prepared }
    }

    /** Total retained bytes for an entry (video snapshot + optional cached audio PCM). */
    private fun entryBytes(e: Entry): Long = e.snapshot.size.toLong() + (e.audioPcm?.size?.toLong() ?: 0L)

    /** Drops any retained snapshot for [uuid]. */
    fun remove(uuid: UUID) {
        entries.invalidate(uuid)
    }

    /** Drops every retained replay snapshot. */
    fun clear() {
        entries.invalidateAll()
    }
}
