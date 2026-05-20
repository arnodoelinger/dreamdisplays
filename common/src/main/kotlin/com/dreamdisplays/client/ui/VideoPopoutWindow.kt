package com.dreamdisplays.client.ui

import me.inotsleep.utils.logging.LoggingManager
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GLCapabilities
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Detached window that mirrors the decoded video, backed by a dedicated `GLFW` window.
 *
 * Thread model:
 *  - `GLFW` window creation / destruction and event callbacks happen on the Minecraft render thread.
 *    On macOS this is the main thread (required by `-XstartOnFirstThread` / `GLFW`).
 *  - [updateFrame] is called from the video reader thread; it does only a very fast byte-copy.
 *  - [renderFrame] is called from the Minecraft render thread (via `DisplayScreen.fitTexture`).
 *    All `GL` work (context switch, texture upload, quad draw, etc.) happens on
 *    that one thread, so `Metal`'s internal resource list is never touched concurrently.
 */
class VideoPopoutWindow(private val onClose: () -> Unit) {

    @Volatile private var frontBytes: ByteArray = ByteArray(0)
    private var backBytes: ByteArray = ByteArray(0)
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0

    @Volatile private var windowHandle = 0L
    private val winW = AtomicInteger(0)
    private val winH = AtomicInteger(0)

    // Render-thread-only GL state
    private var renderer: QuadRenderer? = null
    private var popoutCaps: GLCapabilities? = null
    private var mcWindowHandle = 0L

    @Volatile private var fullscreen = false
    private var savedX = 0; private var savedY = 0
    private var savedW = 0; private var savedH = 0

    val isOpen: Boolean get() = windowHandle != 0L

    /** Fast byte-copy frame updater. */
    fun updateFrame(buf: ByteBuffer, w: Int, h: Int) {
        if (windowHandle == 0L) return
        val size = w * h * 3
        if (backBytes.size != size) backBytes = ByteArray(size)
        buf.get(backBytes)
        val tmp = frontBytes
        frontBytes = backBytes
        backBytes = if (tmp.size == size) tmp else ByteArray(size)
        frameW = w; frameH = h
    }

    /**
     * Renders the current frame to the popout window.
     * Must be called from the Minecraft render thread.
     * Briefly switches GL context to the popout window and back.
     */
    fun renderFrame() {
        val handle = windowHandle
        if (handle == 0L) return
        val fw = frameW; val fh = frameH; val bytes = frontBytes
        val vw = winW.get(); val vh = winH.get()
        if (fw <= 0 || fh <= 0 || bytes.size < fw * fh * 3 || vw <= 0 || vh <= 0) return

        val mainCaps = GL.getCapabilities()

        GLFW.glfwMakeContextCurrent(handle)
        val caps = popoutCaps
        if (caps == null) {
            popoutCaps = GL.createCapabilities()
        } else {
            GL.setCapabilities(caps)
        }

        val r = renderer ?: QuadRenderer().also { renderer = it }
        r.upload(bytes, fw, fh)
        r.draw(vw, vh, fw, fh)
        GLFW.glfwSwapBuffers(handle)

        GLFW.glfwMakeContextCurrent(mcWindowHandle)
        GL.setCapabilities(mainCaps)
    }

    /** Opens (or re-shows) the popout. Safe to call from any thread, dispatches to render thread. */
    fun open(videoW: Int, videoH: Int) {
        Minecraft.getInstance().execute {
            if (windowHandle != 0L) {
                GLFW.glfwShowWindow(windowHandle)
                GLFW.glfwFocusWindow(windowHandle)
                return@execute
            }
            createGlfwWindow(videoW, videoH)
        }
    }

    /** Closes the popout. Safe to call from any thread. */
    fun close() {
        Minecraft.getInstance().execute { destroyWindow() }
    }

    private fun createGlfwWindow(videoW: Int, videoH: Int) {
        val mc = Minecraft.getInstance()
        mcWindowHandle = mc.window.handle()

        val w = videoW.coerceIn(480, 1280)
        val h = videoH.coerceIn(270, 720)

        GLFW.glfwDefaultWindowHints()
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_TRUE)
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2)
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GL11.GL_TRUE)

        // Separate context
        val handle = GLFW.glfwCreateWindow(w, h, "Dream Displays", 0L, 0L)
        if (handle == 0L) {
            LoggingManager.warn("[VideoPopoutWindow] glfwCreateWindow failed")
            return
        }

        windowHandle = handle
        winW.set(w); winH.set(h)

        GLFW.glfwSetWindowCloseCallback(handle) { _ -> destroyWindow() }
        GLFW.glfwSetKeyCallback(handle) { _, key, _, action, _ ->
            if (action == GLFW.GLFW_PRESS) when (key) {
                GLFW.GLFW_KEY_ESCAPE -> destroyWindow()
                GLFW.GLFW_KEY_F      -> toggleFullscreen(handle)
            }
        }
        GLFW.glfwSetFramebufferSizeCallback(handle) { _, fw, fh ->
            winW.set(fw); winH.set(fh)
        }
        val wArr = IntArray(1); val hArr = IntArray(1)
        GLFW.glfwGetFramebufferSize(handle, wArr, hArr)
        if (wArr[0] > 0 && hArr[0] > 0) { winW.set(wArr[0]); winH.set(hArr[0]) }
    }

    private fun destroyWindow() {
        val handle = windowHandle
        windowHandle = 0L
        if (handle != 0L && renderer != null) {
            val mainCaps = GL.getCapabilities()
            GLFW.glfwMakeContextCurrent(handle)
            GL.setCapabilities(popoutCaps ?: GL.createCapabilities())
            renderer?.cleanup()
            renderer = null
            popoutCaps = null
            GLFW.glfwMakeContextCurrent(mcWindowHandle)
            GL.setCapabilities(mainCaps)
            GLFW.glfwDestroyWindow(handle)
        } else if (handle != 0L) {
            GLFW.glfwDestroyWindow(handle)
        }
        onClose()
    }

    private fun toggleFullscreen(handle: Long) {
        if (!fullscreen) {
            val monitor = GLFW.glfwGetPrimaryMonitor()
            if (monitor == 0L) return
            val mode = GLFW.glfwGetVideoMode(monitor) ?: return
            val xArr = IntArray(1); val yArr = IntArray(1)
            GLFW.glfwGetWindowPos(handle, xArr, yArr)
            savedX = xArr[0]; savedY = yArr[0]; savedW = winW.get(); savedH = winH.get()
            GLFW.glfwSetWindowMonitor(handle, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate())
            fullscreen = true
        } else {
            GLFW.glfwSetWindowMonitor(handle, 0L, savedX, savedY, savedW, savedH, GLFW.GLFW_DONT_CARE)
            fullscreen = false
        }
    }

    companion object {
        const val isAvailable: Boolean = true
    }
}

/** Minimal OpenGL 3.2 renderer. */
private class QuadRenderer {

    private val texId: Int = GL11.glGenTextures()
    private val vao: Int = GL30.glGenVertexArrays()
    private val vbo: Int = GL15.glGenBuffers()
    private val program: Int = buildProgram()

    private var texW = 0; private var texH = 0
    private var uploadBuf: ByteBuffer = ByteBuffer.allocateDirect(0)

    init {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)

        GL30.glBindVertexArray(vao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
        val verts = floatArrayOf(
            -1f,  1f,  0f, 0f,
            -1f, -1f,  0f, 1f,
             1f, -1f,  1f, 1f,
            -1f,  1f,  0f, 0f,
             1f, -1f,  1f, 1f,
             1f,  1f,  1f, 0f,
        )
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, verts, GL15.GL_STATIC_DRAW)
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0L)
        GL20.glEnableVertexAttribArray(0)
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8L)
        GL20.glEnableVertexAttribArray(1)
        GL30.glBindVertexArray(0)
    }

    fun upload(bytes: ByteArray, w: Int, h: Int) {
        val size = bytes.size
        if (uploadBuf.capacity() < size) uploadBuf = ByteBuffer.allocateDirect(size)
        uploadBuf.clear(); uploadBuf.put(bytes, 0, size); uploadBuf.flip()

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
        if (texW != w || texH != h) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, uploadBuf)
            texW = w; texH = h
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, uploadBuf)
        }
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
    }

    fun draw(vw: Int, vh: Int, fw: Int, fh: Int) {
        val scale = minOf(vw.toFloat() / fw, vh.toFloat() / fh)
        val dw = (fw * scale).toInt(); val dh = (fh * scale).toInt()
        val ox = (vw - dw) / 2; val oy = (vh - dh) / 2

        GL11.glClearColor(0f, 0f, 0f, 1f)
        GL11.glViewport(0, 0, vw, vh)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        GL11.glViewport(ox, oy, dw, dh)

        GL20.glUseProgram(program)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId)
        GL30.glBindVertexArray(vao)
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6)
        GL30.glBindVertexArray(0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        GL20.glUseProgram(0)
    }

    fun cleanup() {
        GL11.glDeleteTextures(texId)
        GL15.glDeleteBuffers(vbo)
        GL30.glDeleteVertexArrays(vao)
        GL20.glDeleteProgram(program)
    }

    companion object {
        private fun buildProgram(): Int {
            val vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER)
            GL20.glShaderSource(vs, """
                #version 150
                in vec2 pos;
                in vec2 uv;
                out vec2 vUv;
                void main() { gl_Position = vec4(pos, 0.0, 1.0); vUv = uv; }
            """.trimIndent())
            GL20.glCompileShader(vs)

            val fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER)
            GL20.glShaderSource(fs, """
                #version 150
                uniform sampler2D tex;
                in vec2 vUv;
                out vec4 fragColor;
                void main() { fragColor = texture(tex, vUv); }
            """.trimIndent())
            GL20.glCompileShader(fs)

            val prog = GL20.glCreateProgram()
            GL20.glAttachShader(prog, vs)
            GL20.glAttachShader(prog, fs)
            GL20.glBindAttribLocation(prog, 0, "pos")
            GL20.glBindAttribLocation(prog, 1, "uv")
            GL20.glLinkProgram(prog)
            GL20.glDetachShader(prog, vs); GL20.glDeleteShader(vs)
            GL20.glDetachShader(prog, fs); GL20.glDeleteShader(fs)
            return prog
        }
    }
}
