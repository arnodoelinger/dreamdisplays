package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.storage.PipPinRecord
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.storage.PipPinStore
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which displays each player has pinned to a Picture-in-Picture overlay, so a pinned
 * display's [com.dreamdisplays.core.protocol.DisplayInfo] can be re-sent (bypassing render
 * distance, the same bypass [FullscreenBroadcastManager] uses) on the next join — otherwise a
 * player's PiP is silently lost every time they reconnect, especially for a display far outside
 * normal render distance. Persists across restarts via [PipPinStore].
 */
object PipPinManager {
    private val logger = LoggerFactory.getLogger("DreamDisplays/PipPinManager")

    private lateinit var transport: PlaybackTransport

    /** Player id -> pinned display ids. */
    private val pins = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    /** Wires the platform transport. */
    fun init(transport: PlaybackTransport) {
        this.transport = transport
    }

    /** Records that [playerId] pinned [displayId] to PiP. */
    fun pin(playerId: UUID, displayId: UUID) {
        pins.getOrPut(playerId) { ConcurrentHashMap.newKeySet() }.add(displayId)
        persist()
    }

    /** Records that [playerId] unpinned [displayId] from PiP. */
    fun unpin(playerId: UUID, displayId: UUID) {
        pins[playerId]?.remove(displayId)
        persist()
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
        if (changed) persist()
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

    /** Persists every pin, or clears the store when none remain. */
    private fun persist() {
        val records = pins.flatMap { (playerId, displayIds) ->
            displayIds.map { displayId -> PipPinRecord(playerId.toString(), displayId.toString()) }
        }
        PipPinStore.save(records)
    }
}
