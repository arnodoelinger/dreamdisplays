package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.api.media.VideoQuality
import com.dreamdisplays.api.playback.FullscreenAckAction
import com.dreamdisplays.api.playback.FullscreenMode
import com.dreamdisplays.core.protocol.FullscreenAck
import com.dreamdisplays.core.protocol.FullscreenState
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.net.ProtocolRouter
import net.minecraft.client.Minecraft
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

    /**
     * How long a delivery waits for the world to finish loading before being shown anyway. Waiting
     * avoids the visible seek a mid-load start causes.
     */
    private const val READY_GRACE_MS = 3_000L

    /** [deadlineMs] of 0 means the give-up clock hasn't started — see [onClientTick]. */
    private class Pending(val state: FullscreenState, val queuedAtMs: Long, var deadlineMs: Long = 0L)

    /** States whose display hasn't loaded yet, keyed by display id. */
    private val pending = ConcurrentHashMap<UUID, Pending>()

    /** Applies [state]: opens the fullscreen overlay when active, tears it down otherwise. */
    fun handle(state: FullscreenState) {
        if (!state.active) {
            pending.remove(state.displayId)
            DisplayRegistry.screens[state.displayId]?.let {
                it.lastFullscreenState = null
                it.deactivateFullscreen()
            }
            return
        }
        val screen = DisplayRegistry.screens[state.displayId]
        if (screen == null || !isClientReady()) {
            pending[state.displayId] = Pending(state, System.currentTimeMillis())
            return
        }
        apply(screen, state)
    }

    /**
     * Retries pending states whose display has since loaded, and gives up on ones that timed out.
     * Called once per client tick.
     *
     * The give-up clock only runs while the client could actually have shown the overlay: right
     * after a proxy server switch the terrain load alone can outlast [PENDING_TIMEOUT_MS], and
     * expiring during it would drop a perfectly live broadcast on the floor.
     */
    fun onClientTick() {
        if (pending.isEmpty()) return
        val ready = isClientReady()
        val now = System.currentTimeMillis()
        for ((displayId, entry) in pending.entries.toList()) {
            val screen = DisplayRegistry.screens[displayId]
            val waitedOut = now - entry.queuedAtMs >= READY_GRACE_MS
            if (screen != null && (ready || waitedOut)) {
                pending.remove(displayId)
                if (!ready) logger.debug("Showing fullscreen for {} before the world settled.", displayId)
                apply(screen, entry.state)
                continue
            }
            if (!ready && !waitedOut) {
                entry.deadlineMs = 0L
            } else if (entry.deadlineMs == 0L) {
                entry.deadlineMs = now + PENDING_TIMEOUT_MS
            } else if (entry.deadlineMs < now) {
                pending.remove(displayId)
                logger.debug("Dropping fullscreen state for {}: display never loaded.", displayId)
            }
        }
    }

    /**
     * Whether the world around the viewer is actually up. A fullscreen delivery that lands
     * mid-terrain-load would start playing before the server's position could be applied, showing a
     * few seconds of the wrong part of the video before snapping — so nothing is shown at all until
     * the chunk the player stands in exists.
     */
    private fun isClientReady(): Boolean = runCatching {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return@runCatching false
        val level = mc.level ?: return@runCatching false
        level.isLoaded(player.blockPosition())
    }.getOrDefault(false)

    /**
     * Activates the overlay, applies the volume / quality hints, and acknowledges delivery.
     * A [FullscreenState.minimized] delivery is still activated first and then collapsed, so PiP
     * takes over the same live player rather than starting a second one.
     */
    private fun apply(screen: DisplayScreen, state: FullscreenState) {
        if (screen.lastFullscreenState == state) {
            val action = if (state.minimized) FullscreenAckAction.MINIMIZED else FullscreenAckAction.SHOWN
            ProtocolRouter.send(FullscreenAck(state.sessionId, action.wire))
            return
        }
        screen.lastFullscreenState = state
        screen.activateFullscreenMode(FullscreenMode.fromWire(state.mode), state.forced, state.sessionId, state.loop)
        if (state.volume >= 0f) screen.volume = state.volume.coerceIn(0f, 1f)
        if (state.quality.isNotEmpty()) screen.quality = VideoQuality.parse(state.quality)
        if (state.minimized) screen.minimizeFullscreenToPip()
        val action = if (state.minimized) FullscreenAckAction.MINIMIZED else FullscreenAckAction.SHOWN
        ProtocolRouter.send(FullscreenAck(state.sessionId, action.wire))
    }

    /** Drops pending state on disconnect. */
    fun reset() {
        pending.clear()
    }
}
