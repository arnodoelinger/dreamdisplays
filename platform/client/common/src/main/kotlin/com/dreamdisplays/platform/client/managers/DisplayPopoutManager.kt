package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.core.getOrNull
import com.dreamdisplays.platform.client.popout.PopoutEvent
import com.dreamdisplays.platform.client.popout.PopoutManager
import com.dreamdisplays.platform.client.ui.PipCorner
import com.dreamdisplays.platform.client.ui.PipOverlay
import com.dreamdisplays.platform.client.ui.PipOverlayManager
import com.dreamdisplays.platform.client.ui.VideoPopoutWindow
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.platform.client.render.toUploadFormat
import org.slf4j.LoggerFactory

/**
 * Owns windowed and Picture-in-Picture popout state for one display screen. Lifecycle changes are
 * published as [PopoutEvent]s through the registered [PopoutManager] so external subscribers see
 * popouts open and close.
 */
class DisplayPopoutManager(
    private val displayScreen: DisplayScreen,
    private val clearPopoutSink: () -> Unit,
) {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/DisplayPopoutManager")

    /** The separate `GLFW` popout window, or `null` when none has been created. */
    private var popoutWindow: VideoPopoutWindow? = null

    /** The in-game PiP overlay, or `null` when none is active. */
    private var pipOverlay: PipOverlay? = null

    /** True while either a window or a PiP overlay is active. */
    val isActive: Boolean
        get() = (popoutWindow?.isOpen == true) || (pipOverlay != null)

    /** Re-attaches the active popout's frame sink to [player] after a player swap. */
    fun attachTo(player: MediaPlayer, contentAspect: () -> Double) {
        popoutWindow?.let { win ->
            if (win.isOpen) {
                player.setPopoutSink { buf, fw, fh, format ->
                    win.updateFrame(
                        buf,
                        fw,
                        fh,
                        contentAspect(),
                        format.toUploadFormat()
                    )
                }
            }
        }
        pipOverlay?.let { overlay ->
            player.setPopoutSink { buf, fw, fh, format ->
                overlay.updateFrame(
                    buf,
                    fw,
                    fh,
                    contentAspect(),
                    format.toUploadFormat()
                )
            }
        }
    }

    /** Draws the latest frame into the popout window, if one is open. */
    fun renderFrame() {
        popoutWindow?.renderFrame()
    }

    /** Opens (or re-sizes) the separate window, closing any active PiP overlay first. */
    fun activateWindowMode(player: MediaPlayer, textureWidth: Int, textureHeight: Int, contentAspect: () -> Double) {
        if (!VideoPopoutWindow.isAvailable) return
        closePipOverlay()
        val win = popoutWindow
        if (win != null && win.isOpen) {
            // TODO: modify window size?
            win.open(textureWidth.takeIf { it > 0 } ?: 1280, textureHeight.takeIf { it > 0 } ?: 720)
            return
        }
        try {
            val w = textureWidth.takeIf { it > 0 } ?: 1280
            val h = textureHeight.takeIf { it > 0 } ?: 720
            val newWin = win ?: VideoPopoutWindow(displayScreen.uuid.toString()) {
                clearPopoutSink()
            }.also { created ->
                popoutWindow = created
                created.on { event -> emit(event) }
            }
            player.setPopoutSink { buf, fw, fh, format ->
                newWin.updateFrame(
                    buf,
                    fw,
                    fh,
                    contentAspect(),
                    format.toUploadFormat()
                )
            }
            newWin.open(w, h)
        } catch (e: Exception) {
            logger.warn("Could not open window: ${e.message}.")
            emit(PopoutEvent.BackendFailed(displayScreen.uuid.toString(), e.message))
        }
    }

    /** Opens an in-game PiP overlay at [corner], closing any open window first. */
    fun activatePipMode(player: MediaPlayer, corner: PipCorner = PipCorner.BOTTOM_RIGHT, contentAspect: () -> Double) {
        popoutWindow?.let { win ->
            if (win.isOpen) {
                player.setPopoutSink(null); win.close()
            }
        }
        closePipOverlay()
        val overlay = PipOverlay(displayScreen, corner)
        if (PipOverlayManager.add(overlay)) {
            pipOverlay = overlay
            player.setPopoutSink { buf, fw, fh, format ->
                overlay.updateFrame(
                    buf,
                    fw,
                    fh,
                    contentAspect(),
                    format.toUploadFormat()
                )
            }
            emit(PopoutEvent.Opened(displayScreen.uuid.toString()))
        } else {
            logger.warn("No PiP corners available (max 4).")
        }
    }

    /** Closes whichever popout mode is active and detaches the frame sink. */
    fun deactivate(player: MediaPlayer?) {
        player?.setPopoutSink(null)
        popoutWindow?.let { if (it.isOpen) it.close() }
        closePipOverlay()
    }

    /** Tears down all popout state when the display is unregistered. */
    fun unregister(player: MediaPlayer?) {
        player?.setPopoutSink(null)
        popoutWindow?.let { if (it.isOpen) it.close() }
        PipOverlayManager.remove(displayScreen)
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

    /** Publishes [event] through the registered [PopoutManager]; silently skipped before bootstrap. */
    private fun emit(event: PopoutEvent) {
        DreamServices.registry.getOrNull<PopoutManager>()?.emit(event)
    }
}
