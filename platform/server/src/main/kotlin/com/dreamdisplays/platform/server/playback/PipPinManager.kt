package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.playback.model.PipPinRecord
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.storage.PipPinStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks which displays each player has pinned to a Picture-in-Picture overlay, so a pinned display's updates keep
 * reaching them out of range.
 */
object PipPinManager {
    private val logger = LoggerFactory.getLogger(javaClass)

    private lateinit var transport: PlaybackTransport

    /** Player id -> pinned display ids. */
    private val pins = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    /**
     * Bounds how often one player's [pin] / [unpin] packets are honored — both are a client-triggered
     * write, and without this a flood forces one full store rewrite per packet.
     */
    private val throttle = ActionThrottle()
    private const val PIN_COOLDOWN_MS = 250L

    /**
     * Hard cap on live pins per player. A pin bypasses render distance forever, so unlike a normal
     * display this cost doesn't self-limit by what the player can actually see.
     */
    private const val MAX_PINS_PER_PLAYER = 16

    /** How long a burst of [pin] / [unpin] calls is coalesced into a single debounced store write. */
    private const val PERSIST_DEBOUNCE_MS = 2_000L

    /** Serializes the debounced writes so two in-flight saves can never race the same store file. */
    private val persistMutex = Mutex()
    private val persistScheduled = AtomicBoolean(false)

    /** Wires the platform transport. */
    fun init(transport: PlaybackTransport) {
        this.transport = transport
    }

    /**
     * Records that [playerId] pinned [displayId] to PiP. Rate-limited per player, capped at
     * [MAX_PINS_PER_PLAYER] live pins, and a no-op when [displayId] doesn't reference a real display —
     * otherwise a hostile client could flood the store with garbage ids for free.
     */
    fun pin(playerId: UUID, displayId: UUID) {
        if (DisplayManager.getDisplayData(displayId) == null) return
        if (!throttle.tryAcquire(playerId, PIN_COOLDOWN_MS)) return
        val displayIds = pins.getOrPut(playerId) { ConcurrentHashMap.newKeySet() }
        if (displayId !in displayIds && displayIds.size >= MAX_PINS_PER_PLAYER) return
        displayIds.add(displayId)
        schedulePersist()
    }

    /** Records that [playerId] unpinned [displayId] from PiP. Rate-limited per player like [pin]. */
    fun unpin(playerId: UUID, displayId: UUID) {
        if (!throttle.tryAcquire(playerId, PIN_COOLDOWN_MS)) return
        pins[playerId]?.remove(displayId)
        schedulePersist()
    }

    /** Re-sends every pinned display's info to [playerId] on join, bypassing render distance. */
    fun onPlayerJoin(playerId: UUID) {
        val displayIds = pins[playerId] ?: return
        for (displayId in displayIds) {
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            transport.sendDisplayInfo(playerId, display, forced = true)
        }
    }

    /** Forgets every pin referencing a now-deleted display. */
    fun onDisplayRemoved(displayId: UUID) {
        var changed = false
        for (displayIds in pins.values) {
            if (displayIds.remove(displayId)) changed = true
        }
        if (changed) schedulePersist()
    }

    /** Restores persisted pins from disk; call once at startup. */
    fun restore() {
        val records = PipPinStore.load()
        if (records.isEmpty()) return
        var restored = 0
        for (record in records) {
            val playerId = runCatching { UUID.fromString(record.playerId) }.getOrNull() ?: continue
            val displayId = runCatching { UUID.fromString(record.displayId) }.getOrNull() ?: continue
            pins.getOrPut(playerId) { ConcurrentHashMap.newKeySet() }.add(displayId)
            restored++
        }
        if (restored > 0) logger.info("Restored $restored persisted PiP pin(s).")
    }

    /**
     * Schedules a debounced store write, coalescing a burst of [pin] / [unpin] calls (e.g. a client flooding the throttle)
     * into one disk write.
     */
    private fun schedulePersist() {
        if (!persistScheduled.compareAndSet(false, true)) return
        ServerCoroutines.io.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistScheduled.set(false)
            persistMutex.withLock { persistNow() }
        }
    }

    /** Persists every pin, or clears the store when none remain. */
    private fun persistNow() {
        val records = pins.flatMap { (playerId, displayIds) ->
            displayIds.map { displayId -> PipPinRecord(playerId.toString(), displayId.toString()) }
        }
        PipPinStore.save(records)
    }
}
