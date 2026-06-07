package com.dreamdisplays.managers

import com.dreamdisplays.client.ui.PipCorner
import com.dreamdisplays.client.ui.PipOverlay
import com.dreamdisplays.client.ui.PipOverlayManager
import com.dreamdisplays.client.ui.VideoPopoutWindow
import com.dreamdisplays.display.DisplayScreen
import com.dreamdisplays.player.MediaPlayer
import org.slf4j.LoggerFactory

/**
 * Owns windowed and Picture-in-Picture popout state for one display screen.
 */
class DisplayPopoutManager(
    private val displayScreen: DisplayScreen,
    private val clearPopoutSink: () -> Unit,
) {
    private var popoutWindow: VideoPopoutWindow? = null
    private var pipOverlay: PipOverlay? = null

    val isActive: Boolean
        get() = (popoutWindow?.isOpen == true) || (pipOverlay != null)

    fun attachTo(player: MediaPlayer, contentAspect: () -> Double) {
        popoutWindow?.let { win ->
            if (win.isOpen) {
                player.setPopoutSink { buf, fw, fh -> win.updateFrame(buf, fw, fh, contentAspect()) }
            }
        }
        pipOverlay?.let { overlay ->
            player.setPopoutSink { buf, fw, fh -> overlay.updateFrame(buf, fw, fh, contentAspect()) }
        }
    }

    fun renderFrame() {
        popoutWindow?.renderFrame()
    }

    fun activateWindowMode(player: MediaPlayer, textureWidth: Int, textureHeight: Int, contentAspect: () -> Double) {
        if (!VideoPopoutWindow.isAvailable) return
        pipOverlay?.startClose()
        pipOverlay = null
        val win = popoutWindow
        if (win != null && win.isOpen) {
            // TODO: modify window size?
            win.open(textureWidth.takeIf { it > 0 } ?: 1280, textureHeight.takeIf { it > 0 } ?: 720)
            return
        }
        try {
            val w = textureWidth.takeIf { it > 0 } ?: 1280
            val h = textureHeight.takeIf { it > 0 } ?: 720
            val newWin = win ?: VideoPopoutWindow {
                clearPopoutSink()
            }.also { popoutWindow = it }
            player.setPopoutSink { buf, fw, fh -> newWin.updateFrame(buf, fw, fh, contentAspect()) }
            newWin.open(w, h)
        } catch (e: Exception) {
            logger.warn("Could not open window: ${e.message}.")
        }
    }

    fun activatePipMode(player: MediaPlayer, corner: PipCorner = PipCorner.BOTTOM_RIGHT, contentAspect: () -> Double) {
        popoutWindow?.let { win -> if (win.isOpen) { player.setPopoutSink(null); win.close() } }
        pipOverlay?.startClose()
        pipOverlay = null
        val overlay = PipOverlay(displayScreen, corner)
        if (PipOverlayManager.add(overlay)) {
            pipOverlay = overlay
            player.setPopoutSink { buf, fw, fh -> overlay.updateFrame(buf, fw, fh, contentAspect()) }
        } else {
            logger.warn("No PiP corners available (max 4).")
        }
    }

    fun deactivate(player: MediaPlayer?) {
        player?.setPopoutSink(null)
        popoutWindow?.let { if (it.isOpen) it.close() }
        pipOverlay?.startClose()
        pipOverlay = null
    }

    fun unregister(player: MediaPlayer?) {
        player?.setPopoutSink(null)
        popoutWindow?.let { if (it.isOpen) it.close() }
        PipOverlayManager.remove(displayScreen)
        pipOverlay = null
    }

    private companion object {
        private val logger = LoggerFactory.getLogger("DreamDisplays/DisplayPopoutManager")
    }
}
