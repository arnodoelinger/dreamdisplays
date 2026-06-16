package com.dreamdisplays.displays

import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.player.preparation.PreparedMedia
import com.dreamdisplays.managers.WarmParkPolicy
import java.util.UUID
import kotlin.math.abs

/** In-memory LRU of native video replay snapshots retained across soft display unloads. */
internal object DisplayReplayCache {
    private const val DEFAULT_TTL_MS = 5L * 60L * 1000L
    private const val DEFAULT_MAX_BYTES = 512L * 1024L * 1024L
    private const val POSITION_TOLERANCE_NS = 1_000_000_000L

    private data class Entry(
        val url: String,
        val positionNanos: Long,
        val snapshot: ByteArray,
        val audioPcm: ByteArray?,
        val prepared: PreparedMedia?,
        val createdAtMs: Long,
    )

    private val lock = Any()
    private val entries = object : LinkedHashMap<UUID, Entry>(16, 0.75f, true) {}
    private var totalBytes = 0L

    /** Snapshot time-to-live in milliseconds. */
    private val ttlMs: Long =
        System.getProperty("dreamdisplays.cache.ttlMs")?.toLongOrNull()?.coerceAtLeast(0L) ?: DEFAULT_TTL_MS

    /** Total JVM-side memory cap for retained replay snapshots. */
    private val maxBytes: Long =
        System.getProperty("dreamdisplays.cache.jvmMaxBytes")?.toLongOrNull()?.coerceAtLeast(0L)
            ?: WarmParkPolicy.replayCacheBudgetBytes.coerceAtMost(DEFAULT_MAX_BYTES)

    /** Stores [snapshot] (plus optional cached [audioPcm] / resolved [prepared] streams) for [uuid]. */
    fun put(uuid: UUID, url: String, positionNanos: Long, snapshot: ByteArray, audioPcm: ByteArray?, prepared: PreparedMedia?) {
        if (snapshot.isEmpty() || ttlMs == 0L || maxBytes == 0L) return
        synchronized(lock) {
            entries.remove(uuid)?.let { totalBytes -= entryBytes(it) }
            val entry = Entry(url, positionNanos, snapshot, audioPcm, prepared, System.currentTimeMillis())
            entries[uuid] = entry
            totalBytes += entryBytes(entry)
            evictExpiredLocked()
            evictOverflowLocked()
        }
    }

    /** Returns and removes a matching one-shot replay bootstrap for [uuid], if still fresh. */
    fun take(uuid: UUID, url: String, positionNanos: Long): MediaPlayer.ReplayBootstrap? {
        synchronized(lock) {
            evictExpiredLocked()
            val entry = entries[uuid] ?: return null
            if (entry.url != url || abs(entry.positionNanos - positionNanos) > POSITION_TOLERANCE_NS) {
                removeLocked(uuid)
                return null
            }
            removeLocked(uuid)
            return MediaPlayer.ReplayBootstrap(entry.snapshot, entry.positionNanos, entry.audioPcm)
                .also { it.prepared = entry.prepared }
        }
    }

    /** Total retained bytes for an entry (video snapshot + optional cached audio PCM). */
    private fun entryBytes(e: Entry): Long = e.snapshot.size.toLong() + (e.audioPcm?.size?.toLong() ?: 0L)

    /** Drops any retained snapshot for [uuid]. */
    fun remove(uuid: UUID) {
        synchronized(lock) { removeLocked(uuid) }
    }

    /** Drops every retained replay snapshot. */
    fun clear() {
        synchronized(lock) { clearLocked() }
    }

    /** Evicts expired entries; caller must hold [lock]. */
    private fun evictExpiredLocked() {
        if (ttlMs <= 0L) {
            clearLocked()
            return
        }
        val now = System.currentTimeMillis()
        val expired = entries.entries
            .asSequence()
            .filter { now - it.value.createdAtMs > ttlMs }
            .map { it.key }
            .toList()
        expired.forEach(::removeLocked)
    }

    /** Evicts least-recently-used snapshots until [maxBytes] is honored. */
    private fun evictOverflowLocked() {
        val iterator = entries.entries.iterator()
        while (totalBytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            totalBytes -= entryBytes(entry.value)
            iterator.remove()
        }
    }

    /** Removes one entry; caller must hold [lock]. */
    private fun removeLocked(uuid: UUID) {
        entries.remove(uuid)?.let { totalBytes -= entryBytes(it) }
    }

    /** Clears the cache; caller must hold [lock]. */
    private fun clearLocked() {
        entries.clear()
        totalBytes = 0L
    }
}
