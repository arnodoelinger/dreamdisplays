package com.dreamdisplays.client.ui

import com.dreamdisplays.client.popout.PopoutEvent
import com.dreamdisplays.client.popout.PopoutWindow
import com.dreamdisplays.client.popout.WindowBackend
import com.dreamdisplays.client.popout.WindowConfig
import com.dreamdisplays.media.api.VideoFrameSink
import com.dreamdisplays.render.AsyncTextureUploader
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GLCapabilities
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
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Detached window that mirrors the decoded video.
 */
class VideoPopoutWindow(
    private val displayId: String,
    private val onClose: () -> Unit,
) : PopoutWindow {

    private val eventListeners = java.util.concurrent.CopyOnWriteArrayList<(PopoutEvent) -> Unit>()

    private fun emitEvent(event: PopoutEvent) = eventListeners.forEach { it(event) }

    private val impl: PopoutBackend =
        if (IS_MACOS) GlfwBackend { emitEvent(PopoutEvent.Closed(displayId)); onClose() }
        else AwtBackend { emitEvent(PopoutEvent.Closed(displayId)); onClose() }

    override val backend: WindowBackend = if (IS_MACOS) WindowBackend.GLFW else WindowBackend.AWT
    override val isOpen: Boolean get() = impl.isOpen
    override val width: Int get() = impl.width
    override val height: Int get() = impl.height

    /** Opens the window using dimensions from [config] and returns a [VideoFrameSink] for pushing frames. */
    override fun open(config: WindowConfig): VideoFrameSink {
        impl.open(config.initialWidth, config.initialHeight)
        emitEvent(PopoutEvent.Opened(displayId))
        return VideoFrameSink { frame ->
            impl.updateFrame(
                ByteBuffer.wrap(frame.data), frame.width, frame.height,
                frame.width.toDouble() / frame.height,
            )
        }
    }

    override fun on(listener: (PopoutEvent) -> Unit): AutoCloseable {
        eventListeners += listener
        return AutoCloseable { eventListeners -= listener }
    }

    /** Updates the current frame buffer. Safe to call from any thread. */
    fun updateFrame(buf: ByteBuffer, w: Int, h: Int, aspect: Double) =
        impl.updateFrame(buf, w, h, aspect)

    /** No-op on AWT (self-driven repaints). On GLFW, renders the current frame to the window. */
    fun renderFrame() = impl.renderFrame()

    /** Opens (or focuses) the window. Safe to call from any thread. */
    fun open(videoW: Int, videoH: Int) {
        val wasOpen = impl.isOpen
        impl.open(videoW, videoH)
        if (!wasOpen) emitEvent(PopoutEvent.Opened(displayId))
    }

    /** Closes the window. Safe to call from any thread. */
    override fun close() = impl.close()

    private interface PopoutBackend {
        val isOpen: Boolean
        val width: Int
        val height: Int
        fun updateFrame(buf: ByteBuffer, w: Int, h: Int, aspect: Double)
        fun renderFrame()
        fun open(videoW: Int, videoH: Int)
        fun close()
    }

    /**
     * Thread model:
     *  - [open] / [close] dispatch to Minecraft's render thread (= main thread on macOS).
     *  - [updateFrame] copies the frame on the video-reader thread via a double buffer.
     *  - [renderFrame] is called from the Minecraft render thread; briefly switches GL context.
     */
    private class GlfwBackend(private val onClose: () -> Unit) : PopoutBackend {

        @Volatile private var frontBuf: ByteBuffer = EMPTY_DIRECT
        private var backBuf: ByteBuffer = EMPTY_DIRECT
        @Volatile private var frameW = 0
        @Volatile private var frameH = 0
        @Volatile private var contentAspect = 0.0
        private val frameVersion = AtomicLong(0)
        private var uploadedVersion = 0L

        @Volatile private var windowHandle = 0L
        private val winW = AtomicInteger(0)
        private val winH = AtomicInteger(0)

        private var renderer: QuadRenderer? = null
        private var popoutCaps: GLCapabilities? = null

        @Volatile private var fullscreen = false
        private var savedX = 0; private var savedY = 0
        private var savedW = 0; private var savedH = 0

        override val isOpen: Boolean get() = windowHandle != 0L
        override val width: Int get() = winW.get()
        override val height: Int get() = winH.get()

        override fun updateFrame(buf: ByteBuffer, w: Int, h: Int, aspect: Double) {
            if (windowHandle == 0L) return
            val size = w * h * 3
            if (size <= 0 || buf.remaining() < size) return
            var back = backBuf
            if (back.capacity() < size) back = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            back.clear()
            val savedLimit = buf.limit(); val savedPos = buf.position()
            buf.limit(savedPos + size)
            back.put(buf)
            buf.limit(savedLimit); buf.position(savedPos)
            back.flip()
            val prev = frontBuf
            frontBuf = back
            backBuf = if (prev.capacity() >= size) prev
                      else ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            contentAspect = aspect
            frameW = w; frameH = h
            frameVersion.incrementAndGet()
        }

        override fun renderFrame() {
            val handle = windowHandle
            if (handle == 0L) return
            val fw = frameW; val fh = frameH; val buf = frontBuf
            val vw = winW.get(); val vh = winH.get()
            if (fw <= 0 || fh <= 0 || buf.remaining() < fw * fh * 3 || vw <= 0 || vh <= 0) return

            val version = frameVersion.get()
            val haveNewFrame = version != uploadedVersion

            val prevCtx = GLFW.glfwGetCurrentContext()
            // Only restore capabilities when there is an active GL context to restore.
            // Skipping setCapabilities(null) avoids corrupting LWJGL state for mods that
            // replace the GL pipeline (e.g. Vulkan-based renderers).
            val prevCaps = if (prevCtx != 0L) runCatching { GL.getCapabilities() }.getOrNull() else null

            GLFW.glfwMakeContextCurrent(handle)
            val caps = popoutCaps
            if (caps == null) popoutCaps = GL.createCapabilities() else GL.setCapabilities(caps)

            val r = renderer ?: QuadRenderer().also { renderer = it }
            if (haveNewFrame) { r.upload(buf, fw, fh); uploadedVersion = version }
            r.draw(vw, vh, contentRect(fw, fh, contentAspect), fw, fh)
            GLFW.glfwSwapBuffers(handle)

            GLFW.glfwMakeContextCurrent(prevCtx)
            if (prevCaps != null) GL.setCapabilities(prevCaps)
        }

        override fun open(videoW: Int, videoH: Int) {
            Minecraft.getInstance().execute {
                if (windowHandle != 0L) {
                    GLFW.glfwShowWindow(windowHandle)
                    GLFW.glfwFocusWindow(windowHandle)
                    return@execute
                }
                createWindow(videoW, videoH)
            }
        }

        override fun close() {
            Minecraft.getInstance().execute { destroyWindow() }
        }

        private fun createWindow(videoW: Int, videoH: Int) {
            val w = videoW.coerceIn(480, 1280)
            val h = videoH.coerceIn(270, 720)

            GLFW.glfwDefaultWindowHints()
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API)
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_TRUE)
            GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2)
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GL11.GL_TRUE)

            val handle = GLFW.glfwCreateWindow(w, h, "Dream Displays", 0L, 0L)
            if (handle == 0L) { logger.warn("glfwCreateWindow failed"); return }

            windowHandle = handle
            winW.set(w); winH.set(h)

            GLFW.glfwSetWindowCloseCallback(handle) { _ -> destroyWindow() }
            GLFW.glfwSetKeyCallback(handle) { _, key, _, action, _ ->
                if (action == GLFW.GLFW_PRESS) when (key) {
                    GLFW.GLFW_KEY_ESCAPE -> destroyWindow()
                    GLFW.GLFW_KEY_F      -> toggleFullscreen(handle)
                }
            }
            GLFW.glfwSetFramebufferSizeCallback(handle) { _, fw, fh -> winW.set(fw); winH.set(fh) }
            val wa = IntArray(1); val ha = IntArray(1)
            GLFW.glfwGetFramebufferSize(handle, wa, ha)
            if (wa[0] > 0 && ha[0] > 0) { winW.set(wa[0]); winH.set(ha[0]) }
        }

        private fun destroyWindow() {
            val handle = windowHandle
            windowHandle = 0L
            if (handle != 0L) {
                if (renderer != null) {
                    val prevCtx = GLFW.glfwGetCurrentContext()
                    val prevCaps = if (prevCtx != 0L) runCatching { GL.getCapabilities() }.getOrNull() else null
                    GLFW.glfwMakeContextCurrent(handle)
                    GL.setCapabilities(popoutCaps ?: GL.createCapabilities())
                    renderer?.cleanup(); renderer = null; popoutCaps = null
                    GLFW.glfwMakeContextCurrent(prevCtx)
                    if (prevCaps != null) GL.setCapabilities(prevCaps)
                }
                GLFW.glfwDestroyWindow(handle)
            }
            onClose()
        }

        private fun toggleFullscreen(handle: Long) {
            if (!fullscreen) {
                val monitor = GLFW.glfwGetPrimaryMonitor()
                if (monitor == 0L) return
                val mode = GLFW.glfwGetVideoMode(monitor) ?: return
                val xa = IntArray(1); val ya = IntArray(1)
                GLFW.glfwGetWindowPos(handle, xa, ya)
                savedX = xa[0]; savedY = ya[0]; savedW = winW.get(); savedH = winH.get()
                GLFW.glfwSetWindowMonitor(handle, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate())
                fullscreen = true
            } else {
                GLFW.glfwSetWindowMonitor(handle, 0L, savedX, savedY, savedW, savedH, GLFW.GLFW_DONT_CARE)
                fullscreen = false
            }
        }

        companion object {
            private val logger = LoggerFactory.getLogger("DreamDisplays/VideoPopout")
            private val EMPTY_DIRECT = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())!!

            private fun contentRect(frameW: Int, frameH: Int, contentAspect: Double): ContentRect {
                if (frameW <= 0 || frameH <= 0) return ContentRect(0, 0, frameW, frameH)
                if (contentAspect <= 0.0 || !contentAspect.isFinite()) return ContentRect(0, 0, frameW, frameH)
                val frameAspect = frameW / frameH.toDouble()
                return if (contentAspect > frameAspect) {
                    val h = (frameW / contentAspect).toInt().coerceIn(1, frameH)
                    ContentRect(0, (frameH - h) / 2, frameW, h)
                } else {
                    val w = (frameH * contentAspect).toInt().coerceIn(1, frameW)
                    ContentRect((frameW - w) / 2, 0, w, frameH)
                }
            }
        }
    }

    /**
     * Thread model:
     *  - [open] / [close] dispatch to the AWT Event Dispatch Thread via [SwingUtilities.invokeLater].
     *  - [updateFrame] copies the frame on the video-reader thread and schedules a repaint.
     *  - [renderFrame] is a no-op: AWT drives its own repaints independently.
     */
    private class AwtBackend(private val onClose: () -> Unit) : PopoutBackend {

        @Volatile private var currentImage: BufferedImage? = null
        @Volatile private var contentAspect = 0.0

        private var frame: JFrame? = null
        private var panel: VideoPanel? = null

        private val logger = LoggerFactory.getLogger("DreamDisplays/VideoPopout")

        override val isOpen: Boolean get() = frame?.isDisplayable == true
        override val width: Int get() = frame?.width ?: 0
        override val height: Int get() = frame?.height ?: 0

        override fun updateFrame(buf: ByteBuffer, w: Int, h: Int, aspect: Double) {
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

        override fun renderFrame() = Unit

        override fun open(videoW: Int, videoH: Int) {
            SwingUtilities.invokeLater {
                val existing = frame
                if (existing != null && existing.isDisplayable) {
                    existing.toFront(); existing.requestFocus(); return@invokeLater
                }
                createFrame(videoW, videoH)
            }
        }

        override fun close() {
            SwingUtilities.invokeLater { destroyFrame() }
        }

        private fun createFrame(videoW: Int, videoH: Int) {
            try {
                val w = videoW.coerceIn(480, 1280)
                val h = videoH.coerceIn(270, 720)
                val p = VideoPanel(); panel = p
                val f = JFrame("Dream Displays"); frame = f
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
                            KeyEvent.VK_F -> {
                                val state = f.extendedState
                                f.extendedState =
                                    if (state and JFrame.MAXIMIZED_BOTH != 0) JFrame.NORMAL
                                    else JFrame.MAXIMIZED_BOTH
                            }
                        }
                    }
                })
                f.isVisible = true
            } catch (e: Exception) {
                logger.error("Failed to create AWT popout window", e)
                frame = null; panel = null
                onClose()
            }
        }

        private fun destroyFrame() {
            frame?.dispose(); frame = null; panel = null; currentImage = null
            onClose()
        }

        inner class VideoPanel : JPanel() {
            init { background = Color.BLACK; isFocusable = true }

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
    }

    companion object {
        private val IS_MACOS = System.getProperty("os.name", "").lowercase().startsWith("mac")

        /**
         * True when a popout window can be opened.
         * Always true on macOS (GLFW backend needs no extra preconditions).
         * On Windows / Linux, false only in headless environments (servers, CI).
         * Evaluated lazily so the AWT check sees the correct [java.awt.headless] value
         * set by [com.dreamdisplays.Initializer] during mod init.
         */
        val isAvailable: Boolean by lazy {
            if (IS_MACOS) true
            else try { !GraphicsEnvironment.isHeadless() } catch (_: Exception) { false }
        }
    }
}

private data class ContentRect(val x: Int, val y: Int, val w: Int, val h: Int)

private class QuadRenderer {
    private val texId = GL11.glGenTextures()
    private val vao = GL30.glGenVertexArrays()
    private val vbo = GL15.glGenBuffers()
    private val program = buildProgram()
    private val uploader = AsyncTextureUploader(stateCache = false)
    private var texW = 0; private var texH = 0

    init {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        GL30.glBindVertexArray(vao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, floatArrayOf(
            -1f,  1f,  0f, 0f,
            -1f, -1f,  0f, 1f,
             1f, -1f,  1f, 1f,
            -1f,  1f,  0f, 0f,
             1f, -1f,  1f, 1f,
             1f,  1f,  1f, 0f,
        ), GL15.GL_STATIC_DRAW)
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0L)
        GL20.glEnableVertexAttribArray(0)
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8L)
        GL20.glEnableVertexAttribArray(1)
        GL30.glBindVertexArray(0)
    }

    fun upload(buf: ByteBuffer, w: Int, h: Int) {
        if (texW != w || texH != h) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId)
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
            val sp = buf.position(); val sl = buf.limit()
            buf.limit(sp + w * h * 3)
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf)
            buf.limit(sl); buf.position(sp)
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            texW = w; texH = h
            return
        }
        uploader.upload(texId, buf, w, h)
    }

    fun draw(vw: Int, vh: Int, content: ContentRect, fw: Int, fh: Int) {
        val scale = minOf(vw.toFloat() / content.w, vh.toFloat() / content.h)
        val dw = (content.w * scale).toInt(); val dh = (content.h * scale).toInt()
        val ox = (vw - dw) / 2;              val oy = (vh - dh) / 2
        GL11.glClearColor(0f, 0f, 0f, 1f)
        GL11.glViewport(0, 0, vw, vh); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        GL11.glViewport(ox, oy, dw, dh)
        GL20.glUseProgram(program)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId)
        val loc = GL20.glGetUniformLocation(program, "crop")
        if (loc >= 0) GL20.glUniform4f(loc,
            content.x / fw.toFloat(), content.y / fh.toFloat(),
            content.w / fw.toFloat(), content.h / fh.toFloat())
        GL30.glBindVertexArray(vao)
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6)
        GL30.glBindVertexArray(0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        GL20.glUseProgram(0)
    }

    fun cleanup() {
        uploader.cleanup()
        GL11.glDeleteTextures(texId)
        GL15.glDeleteBuffers(vbo)
        GL30.glDeleteVertexArrays(vao)
        GL20.glDeleteProgram(program)
    }

    companion object {
        private fun buildProgram(): Int {
            fun shader(type: Int, src: String): Int =
                GL20.glCreateShader(type).also { GL20.glShaderSource(it, src); GL20.glCompileShader(it) }
            val vs = shader(GL20.GL_VERTEX_SHADER, """
                #version 150
                in vec2 pos; in vec2 uv; out vec2 vUv;
                void main() { gl_Position = vec4(pos, 0.0, 1.0); vUv = uv; }
            """.trimIndent())
            val fs = shader(GL20.GL_FRAGMENT_SHADER, """
                #version 150
                uniform sampler2D tex; uniform vec4 crop;
                in vec2 vUv; out vec4 fragColor;
                void main() { fragColor = texture(tex, crop.xy + vUv * crop.zw); }
            """.trimIndent())
            return GL20.glCreateProgram().also { p ->
                GL20.glAttachShader(p, vs); GL20.glAttachShader(p, fs)
                GL20.glBindAttribLocation(p, 0, "pos")
                GL20.glBindAttribLocation(p, 1, "uv")
                GL20.glLinkProgram(p)
                GL20.glDetachShader(p, vs); GL20.glDeleteShader(vs)
                GL20.glDetachShader(p, fs); GL20.glDeleteShader(fs)
            }
        }
    }
}
