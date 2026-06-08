package com.dreamdisplays.client.ui

import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Detached window that mirrors the decoded video, backed by an AWT [JFrame].
 *
 * Thread model:
 *  - [open] and [close] are safe to call from any thread; they dispatch to the AWT event queue.
 *  - [updateFrame] is called from the video reader thread; it does a fast pixel copy and schedules
 *    a repaint on the AWT event queue.
 *  - [renderFrame] is a no-op: AWT drives its own repaints.
 */
class VideoPopoutWindow(private val onClose: () -> Unit) {

    @Volatile private var currentImage: BufferedImage? = null
    @Volatile private var contentAspect = 0.0

    private var frame: JFrame? = null
    private var panel: VideoPanel? = null

    val isOpen: Boolean get() = frame?.isDisplayable == true

    private val logger = LoggerFactory.getLogger("DreamDisplays/VideoPopout")

    /** Copies the decoded RGB frame into a [BufferedImage] and schedules a repaint. */
    fun updateFrame(buf: ByteBuffer, w: Int, h: Int, aspect: Double) {
        if (!isOpen || w <= 0 || h <= 0 || buf.remaining() < w * h * 3) return

        val img = currentImage?.takeIf { it.width == w && it.height == h }
            ?: BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)

        val pixels = (img.raster.dataBuffer as DataBufferInt).data
        val src = buf.duplicate()
        for (i in pixels.indices) {
            val r = src.get().toInt() and 0xFF
            val g = src.get().toInt() and 0xFF
            val b = src.get().toInt() and 0xFF
            pixels[i] = (r shl 16) or (g shl 8) or b
        }
        contentAspect = aspect
        currentImage = img
        panel?.repaint()
    }

    /** No-op: AWT drives its own repaints independently of the Minecraft render thread. */
    fun renderFrame() = Unit

    /** Opens (or focuses) the window. Safe to call from any thread. */
    fun open(videoW: Int, videoH: Int) {
        SwingUtilities.invokeLater {
            val existing = frame
            if (existing != null && existing.isDisplayable) {
                existing.toFront()
                existing.requestFocus()
                return@invokeLater
            }
            createFrame(videoW, videoH)
        }
    }

    /** Closes the window. Safe to call from any thread. */
    fun close() {
        SwingUtilities.invokeLater { destroyFrame() }
    }

    /** Initializes the JFrame and its content panel, and sets up event listeners for closing and fullscreen. */
    private fun createFrame(videoW: Int, videoH: Int) {
        val w = videoW.coerceIn(480, 1280)
        val h = videoH.coerceIn(270, 720)

        val p = VideoPanel()
        panel = p

        val f = JFrame("Dream Displays")
        frame = f
        f.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        f.contentPane = p
        f.setSize(w, h)
        f.setLocationRelativeTo(null)

        f.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = destroyFrame()
        })
        f.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ESCAPE -> destroyFrame()
                    KeyEvent.VK_F -> toggleFullscreen(f)
                }
            }
        })

        f.isVisible = true
    }

    /** Frame destroy logic, shared by both the close button and the ESC key. */
    private fun destroyFrame() {
        frame?.dispose()
        frame = null
        panel = null
        currentImage = null
        onClose()
    }

    /** Toggles fullscreen mode for the given frame. */
    private fun toggleFullscreen(f: JFrame) {
        val state = f.extendedState
        f.extendedState = if (state and JFrame.MAXIMIZED_BOTH != 0) JFrame.NORMAL else JFrame.MAXIMIZED_BOTH
    }

    /** Custom panel that paints the current video frame, letterboxing as needed. */
    private inner class VideoPanel : JPanel() {
        init {
            background = Color.BLACK
            isFocusable = true
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val img = currentImage ?: return
            val aspect = contentAspect
            val vw = width; val vh = height
            if (vw <= 0 || vh <= 0) return

            val drawW: Int; val drawH: Int; val ox: Int; val oy: Int
            if (aspect > 0.0 && aspect.isFinite()) {
                val panelAspect = vw.toDouble() / vh
                if (aspect > panelAspect) {
                    drawW = vw
                    drawH = (vw / aspect).toInt().coerceIn(1, vh)
                    ox = 0; oy = (vh - drawH) / 2
                } else {
                    drawW = (vh * aspect).toInt().coerceIn(1, vw)
                    drawH = vh
                    ox = (vw - drawW) / 2; oy = 0
                }
            } else {
                drawW = vw; drawH = vh; ox = 0; oy = 0
            }

            g.drawImage(img, ox, oy, drawW, drawH, null)
        }
    }

    companion object {
        /** False only in a headless environment where AWT is unavailable. */
        val isAvailable: Boolean = !GraphicsEnvironment.isHeadless()
    }
}
