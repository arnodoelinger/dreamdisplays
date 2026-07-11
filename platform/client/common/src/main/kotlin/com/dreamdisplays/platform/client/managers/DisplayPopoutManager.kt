package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.api.media.FramePixelFormat
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.api.runtime.getOrNull
import com.dreamdisplays.api.playback.FullscreenMode
import com.dreamdisplays.platform.client.popout.PopoutEvent
import com.dreamdisplays.platform.client.popout.PopoutManager
import com.dreamdisplays.platform.client.ui.FullscreenOverlay
import com.dreamdisplays.platform.client.ui.FullscreenOverlayManager
import com.dreamdisplays.platform.client.ui.PipCorner
import com.dreamdisplays.platform.client.ui.PipOverlay
import com.dreamdisplays.platform.client.ui.PipOverlayManager
import com.dreamdisplays.platform.client.ui.VideoPopoutWindow
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.platform.client.render.toUploadFormat
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

/**
 * Owns windowed, Picture-in-Picture, and fullscreen popout state for one display screen. All active
 * surfaces are fed through a single fan-out sink on the player so they can coexist. Lifecycle
 * changes are published as [PopoutEvent]s through the registered [PopoutManager] so external
 * subscribers see popouts open and close.
 */
class DisplayPopoutManager(
    private val displayScreen: DisplayScreen,
) {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/DisplayPopoutManager")

    /** The separate `GLFW` popout window, or `null` when none has been created. */
    private var popoutWindow: VideoPopoutWindow? = null

    /** True while the window is meant to receive frames; window open/close is async, so [VideoPopoutWindow.isOpen] lags. */
    private var windowActive = false

    /** The in-game PiP overlay, or `null` when none is active. */
    private var pipOverlay: PipOverlay? = null

    /** The fullscreen overlay, or `null` when none is active. */
    private var fullscreenOverlay: FullscreenOverlay? = null

    /** The player currently feeding the popout surfaces, kept for sink re-wiring. */
    private var currentPlayer: MediaPlayer? = null

    /** Supplier of the current video content aspect, captured on attach / activate. */
    private var contentAspect: () -> Double = { 16.0 / 9.0 }

    /** True while a window, PiP overlay, or fullscreen overlay is active. */
    val isActive: Boolean
        get() = windowActive || (popoutWindow?.isOpen == true) || (pipOverlay != null) || (fullscreenOverlay != null)

    /** True while the fullscreen overlay is active. */
    val isFullscreenActive: Boolean get() = fullscreenOverlay != null

    /** Re-attaches all active popout frame sinks to [player] after a player swap. */
    fun attachTo(player: MediaPlayer, contentAspect: () -> Double) {
        currentPlayer = player
        this.contentAspect = contentAspect
        rewireSink()
    }

    /** Rebuilds the player's single popout sink to fan frames out to every active surface. */
    private fun rewireSink() {
        val player = currentPlayer ?: return
        val aspect = contentAspect
        val targets = buildList<(ByteBuffer, Int, Int, FramePixelFormat) -> Unit> {
            popoutWindow?.takeIf { windowActive }?.let { win ->
                add { buf, fw, fh, format -> win.updateFrame(buf, fw, fh, aspect(), format.toUploadFormat()) }
            }
            pipOverlay?.let { overlay ->
                add { buf, fw, fh, format -> overlay.updateFrame(buf, fw, fh, aspect(), format.toUploadFormat()) }
            }
            fullscreenOverlay?.let { overlay ->
                add { buf, fw, fh, format -> overlay.updateFrame(buf, fw, fh, aspect(), format.toUploadFormat()) }
            }
        }
        when (targets.size) {
            0 -> player.setPopoutSink(null)
            1 -> player.setPopoutSink(targets[0])
            else -> player.setPopoutSink { buf, fw, fh, format -> for (t in targets) t(buf, fw, fh, format) }
        }
    }

    /** Draws the latest frame into the popout window, if one is open. */
    fun renderFrame() {
        popoutWindow?.renderFrame()
    }

    /** Opens (or re-sizes) the separate window, closing any active PiP overlay first. */
    fun activateWindowMode(player: MediaPlayer, textureWidth: Int, textureHeight: Int, contentAspect: () -> Double) {
        if (!VideoPopoutWindow.isAvailable) return
        currentPlayer = player
        this.contentAspect = contentAspect
        closePipOverlay()
        val win = popoutWindow
        if (win != null && win.isOpen) {
            // TODO: modify window size?
            win.open(textureWidth.takeIf { it > 0 } ?: 1280, textureHeight.takeIf { it > 0 } ?: 720)
            windowActive = true
            rewireSink()
            return
        }
        try {
            val w = textureWidth.takeIf { it > 0 } ?: 1280
            val h = textureHeight.takeIf { it > 0 } ?: 720
            val newWin = win ?: VideoPopoutWindow(displayScreen.uuid.toString()) {
                windowActive = false
                rewireSink()
            }.also { created ->
                popoutWindow = created
                created.on { event -> emit(event) }
            }
            newWin.open(w, h)
            windowActive = true
            rewireSink()
        } catch (e: Exception) {
            logger.warn("Could not open window: ${e.message}.")
            emit(PopoutEvent.BackendFailed(displayScreen.uuid.toString(), e.message))
        }
    }

    /** Opens an in-game PiP overlay at [corner], closing any open window and fullscreen overlay first. */
    fun activatePipMode(
        player: MediaPlayer,
        corner: PipCorner = PipCorner.BOTTOM_RIGHT,
        interactive: Boolean = true,
        initialSizeFraction: Float = 0.25f,
        contentAspect: () -> Double,
    ) {
        currentPlayer = player
        this.contentAspect = contentAspect
        popoutWindow?.let { win -> if (win.isOpen) win.close() }
        windowActive = false
        closeFullscreenOverlay()
        closePipOverlay()
        val overlay = PipOverlay(displayScreen, corner, interactive, initialSizeFraction)
        if (PipOverlayManager.add(overlay)) {
            pipOverlay = overlay
            rewireSink()
            emit(PopoutEvent.Opened(displayScreen.uuid.toString()))
        } else {
            rewireSink()
            logger.warn("No PiP corners available.")
        }
    }

    /** Opens the fullscreen overlay, closing any PiP overlay of this display first. */
    fun activateFullscreenMode(
        player: MediaPlayer,
        mode: FullscreenMode,
        forced: Boolean = false,
        sessionId: String? = null,
        contentAspect: () -> Double,
    ) {
        currentPlayer = player
        this.contentAspect = contentAspect
        closePipOverlay()
        closeFullscreenOverlay()
        fullscreenOverlay = FullscreenOverlayManager.open(displayScreen, mode, forced, sessionId)
        rewireSink()
        emit(PopoutEvent.Opened(displayScreen.uuid.toString()))
    }

    /** Closes only the fullscreen overlay, keeping window / PiP surfaces alive. */
    fun deactivateFullscreen(player: MediaPlayer?) {
        player?.let { currentPlayer = it }
        if (fullscreenOverlay == null) return
        closeFullscreenOverlay()
        rewireSink()
    }

    /** Swaps the active fullscreen overlay for a PiP overlay in one step, at double the normal PiP size. */
    fun minimizeFullscreenToPip(player: MediaPlayer?, interactive: Boolean = true) {
        val mp = player ?: currentPlayer ?: return
        if (fullscreenOverlay == null) return
        activatePipMode(mp, PipCorner.BOTTOM_RIGHT, interactive, initialSizeFraction = 0.5f, contentAspect = contentAspect)
    }

    /** Closes every popout mode and detaches the frame sink. */
    fun deactivate(player: MediaPlayer?) {
        player?.setPopoutSink(null)
        popoutWindow?.let { if (it.isOpen) it.close() }
        windowActive = false
        closeFullscreenOverlay()
        closePipOverlay()
    }

    /** Tears down all popout state when the display is unregistered. */
    fun unregister(player: MediaPlayer?) {
        player?.setPopoutSink(null)
        popoutWindow?.let { if (it.isOpen) it.close() }
        windowActive = false
        PipOverlayManager.remove(displayScreen)
        FullscreenOverlayManager.remove(displayScreen)
        fullscreenOverlay = null
        if (pipOverlay != null) {
            pipOverlay = null
            emit(PopoutEvent.Closed(displayScreen.uuid.toString()))
        }
    }

    /** Starts the PiP close animation and announces the closure; no-op when no PiP is open. */
    private fun closePipOverlay() {
        val overlay = pipOverlay ?: return
        overlay.startClose()
        pipOverlay = null
        emit(PopoutEvent.Closed(displayScreen.uuid.toString()))
    }

    /** Starts the fullscreen close animation and announces the closure; no-op when none is open. */
    private fun closeFullscreenOverlay() {
        val overlay = fullscreenOverlay ?: return
        overlay.startClose()
        fullscreenOverlay = null
        emit(PopoutEvent.Closed(displayScreen.uuid.toString()))
    }

    /** Publishes [event] through the registered [PopoutManager]; silently skipped before bootstrap. */
    private fun emit(event: PopoutEvent) {
        DreamServices.registry.getOrNull<PopoutManager>()?.emit(event)
    }
}
