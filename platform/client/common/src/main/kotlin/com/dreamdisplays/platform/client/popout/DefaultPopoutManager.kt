package com.dreamdisplays.platform.client.popout

import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.media.sink.VideoFrameSink
import com.dreamdisplays.api.playback.FullscreenMode
import com.dreamdisplays.api.runtime.getOrNull
import com.dreamdisplays.core.protocol.PipPin
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.managers.DisplayPopoutManager
import com.dreamdisplays.platform.client.overlay.OverlayManager
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Global [PopoutManager] facade. Delegates per-display window and PiP operations to the
 * [DisplayPopoutManager] embedded in each [DisplayScreen]
 * (looked up via [DisplayRegistry]), and queries [OverlayManager] for PiP status.
 *
 * Frame-sink wiring remains internal to [DisplayPopoutManager]; [openWindow] and
 * [openPip] return null because the sink is set directly on the player, not surfaced here.
 */
class DefaultPopoutManager : PopoutManager {
    /** Thread-safe list of subscribers to [PopoutEvent]s, which are emitted by the per-screen popout managers and fanned out here. */
    private val listeners = CopyOnWriteArrayList<(PopoutEvent) -> Unit>()

    /** Opens or focuses the detached window for [displayId]. Returns null, the sink is managed internally. */
    override fun openWindow(displayId: DisplayId, config: WindowConfig): VideoFrameSink? {
        DisplayRegistry.screens[displayId.uuid]?.activateWindowMode()
        return null
    }

    /**
     * Opens the in-game PiP overlay for [displayId] and pins it server-side so it re-opens on the
     * next join even if the display is then outside normal render distance. Returns null, the sink
     * is managed internally.
     */
    override fun openPip(displayId: DisplayId): VideoFrameSink? {
        DisplayRegistry.screens[displayId.uuid]?.activatePipMode()
        ClientSettingsStore.setPipOpen(displayId.uuid, true)
        Initializer.sendPacket(PipPin(displayId.uuid, pinned = true))
        return null
    }

    /** Closes whichever popout mode is active for [displayId], unpinning it if it was PiP. */
    override fun close(displayId: DisplayId) {
        val wasPip = isPipOpen(displayId)
        DisplayRegistry.screens[displayId.uuid]?.deactivatePopout()
        if (wasPip) {
            ClientSettingsStore.setPipOpen(displayId.uuid, false)
            Initializer.sendPacket(PipPin(displayId.uuid, pinned = false))
        }
    }

    /** Shows [displayId] as a fullscreen overlay in [mode]. */
    override fun openFullscreen(displayId: DisplayId, mode: FullscreenMode) {
        DisplayRegistry.screens[displayId.uuid]?.activateFullscreenMode(mode)
    }

    /** Closes the fullscreen overlay of [displayId]. */
    override fun closeFullscreen(displayId: DisplayId) {
        DisplayRegistry.screens[displayId.uuid]?.deactivateFullscreen()
    }

    /** True if [displayId] is currently shown in the fullscreen overlay. */
    override fun isFullscreenOpen(displayId: DisplayId): Boolean =
        DisplayRegistry.screens[displayId.uuid]?.isFullscreenActive == true

    /** Deactivates all popouts on all loaded displays, then triggers a full [OverlayManager.closeAll]. */
    override fun closeAll() {
        DisplayRegistry.getScreens().forEach { it.deactivatePopout() }
        DreamServices.registry.getOrNull<OverlayManager>()?.closeAll()
    }

    /**
     * True if [displayId] has a detached window open. Inferred as "popout is active but no PiP overlay
     * is registered with the [OverlayManager]".
     */
    override fun isWindowOpen(displayId: DisplayId): Boolean {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return false
        return screen.isPopoutActive && !isPipOpen(displayId) && !isFullscreenOpen(displayId)
    }

    /** True if [displayId] has an active PiP overlay registered with the [OverlayManager]. */
    override fun isPipOpen(displayId: DisplayId): Boolean =
        DreamServices.registry.getOrNull<OverlayManager>()?.getOverlay(displayId) != null

    /** Not surfaced. The frame sink lives inside the per-screen player pipeline. Always returns null. */
    override fun getPopoutSink(displayId: DisplayId): VideoFrameSink? = null

    /** Subscribes [listener] to [PopoutEvent]s; close the returned handle to unsubscribe. */
    override fun on(listener: (PopoutEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Fans [event] out to every subscriber. Emitted by [DisplayPopoutManager]. */
    override fun emit(event: PopoutEvent) {
        listeners.forEach { it(event) }
    }
}
