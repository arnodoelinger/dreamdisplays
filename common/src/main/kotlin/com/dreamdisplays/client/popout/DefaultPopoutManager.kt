package com.dreamdisplays.client.popout

import com.dreamdisplays.api.DisplayId
import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.client.overlay.OverlayManager
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.media.api.VideoFrameSink
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Global [PopoutManager] facade. Delegates per-display window and PiP operations to the
 * [com.dreamdisplays.managers.DisplayPopoutManager] embedded in each [com.dreamdisplays.displays.DisplayScreen]
 * (looked up via [DisplayRegistry]), and queries [OverlayManager] for PiP status.
 *
 * Frame-sink wiring remains internal to [com.dreamdisplays.managers.DisplayPopoutManager]; [openWindow] and
 * [openPip] return null because the sink is set directly on the player, not surfaced here.
 *
 * @since 1.7.0
 */
class DefaultPopoutManager : PopoutManager {
    /** Thread-safe list of subscribers to [PopoutEvent]s, which are emitted by the per-screen popout managers and fanned out here. */
    private val listeners = CopyOnWriteArrayList<(PopoutEvent) -> Unit>()

    /** Opens or focuses the detached window for [displayId]. Returns null, the sink is managed internally. */
    override fun openWindow(displayId: DisplayId, config: WindowConfig): VideoFrameSink? {
        DisplayRegistry.screens[displayId.uuid]?.activateWindowMode()
        return null
    }

    /** Opens the in-game PiP overlay for [displayId]. Returns null, the sink is managed internally. */
    override fun openPip(displayId: DisplayId): VideoFrameSink? {
        DisplayRegistry.screens[displayId.uuid]?.activatePipMode()
        return null
    }

    /** Closes whichever popout mode is active for [displayId]. */
    override fun close(displayId: DisplayId) {
        DisplayRegistry.screens[displayId.uuid]?.deactivatePopout()
    }

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
        return screen.isPopoutActive && !isPipOpen(displayId)
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

    /** Fans [event] out to every subscriber. Emitted by [com.dreamdisplays.managers.DisplayPopoutManager]. */
    override fun emit(event: PopoutEvent) {
        listeners.forEach { it(event) }
    }
}
