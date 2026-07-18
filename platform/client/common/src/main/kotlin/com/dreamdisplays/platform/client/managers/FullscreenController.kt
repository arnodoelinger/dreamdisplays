package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.api.media.VideoQuality
import com.dreamdisplays.api.playback.FullscreenAckAction
import com.dreamdisplays.api.playback.FullscreenMode
import com.dreamdisplays.core.protocol.FullscreenAck
import com.dreamdisplays.core.protocol.FullscreenState
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.net.ProtocolRouter
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies incoming [FullscreenState] snapshots to the matching [DisplayScreen], opening or closing
 * the fullscreen overlay and acknowledging the transition back to the server. A state that arrives
 * before its display's `DisplayInfo` (same delivery, but over the network) is retried for a few
 * seconds via [onClientTick] rather than dropped.
 */
object FullscreenController {
    private val logger = LoggerFactory.getLogger("DreamDisplays/FullscreenController")

    /** How long a state waits for its display to load before being given up on. */
    private const val PENDING_TIMEOUT_MS = 5_000L

    private data class Pending(val state: FullscreenState, val expiresAtMs: Long)

    /** States whose display hasn't loaded yet, keyed by display id. */
    private val pending = ConcurrentHashMap<UUID, Pending>()

    /** Applies [state]: opens the fullscreen overlay when active, tears it down otherwise. */
    fun handle(state: FullscreenState) {
        if (!state.active) {
            pending.remove(state.displayId)
            DisplayRegistry.screens[state.displayId]?.deactivateFullscreen()
            return
        }
        val screen = DisplayRegistry.screens[state.displayId]
        if (screen == null) {
            pending[state.displayId] = Pending(state, System.currentTimeMillis() + PENDING_TIMEOUT_MS)
            return
        }
        apply(screen, state)
    }

    /** Retries pending states whose display has since loaded, and gives up on ones that timed out. Called once per client tick. */
    fun onClientTick() {
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        val due = pending.entries
            .filter { (id, p) -> DisplayRegistry.screens.containsKey(id) || p.expiresAtMs < now }
            .map { it.key }
        for (displayId in due) {
            val entry = pending.remove(displayId) ?: continue
            val screen = DisplayRegistry.screens[displayId]
            if (screen == null) {
                logger.debug("Dropping fullscreen state for {}: display never loaded.", displayId)
                continue
            }
            apply(screen, entry.state)
        }
    }

    /** Activates the overlay, applies the volume / quality hints, and acknowledges delivery. */
    private fun apply(screen: DisplayScreen, state: FullscreenState) {
        screen.activateFullscreenMode(FullscreenMode.fromWire(state.mode), state.forced, state.sessionId, state.loop)
        if (state.volume >= 0f) screen.volume = state.volume.coerceIn(0f, 1f)
        if (state.quality.isNotEmpty()) screen.quality = VideoQuality.parse(state.quality)
        ProtocolRouter.send(FullscreenAck(state.sessionId, FullscreenAckAction.SHOWN.wire))
    }

    /** Drops pending state on disconnect. */
    fun reset() {
        pending.clear()
    }
}
