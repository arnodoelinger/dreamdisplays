package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.api.playback.FullscreenMode
import com.dreamdisplays.platform.client.render.AsyncTextureUploader
import com.dreamdisplays.platform.client.render.TextureUploadUtil
import com.dreamdisplays.platform.client.render.UploadPixelFormat
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.drawOutline
import com.dreamdisplays.platform.client.ui.kit.scaleAlpha
import com.mojang.blaze3d.platform.NativeImage
//? if >=1.21.11 {
//?} else
/*import com.mojang.blaze3d.systems.RenderSystem*/
import net.minecraft.client.Minecraft
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
//? if >=1.21.11 {
import net.minecraft.client.renderer.RenderPipelines
//?}
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Screen-covering video overlay for one display screen.
 *
 * Unlike [PipOverlay] it has no anchors, dragging, or resize: the video is letterboxed into the
 * whole screen ([FullscreenMode.IMMERSIVE]) or into a slightly inset area over a translucent scrim
 * ([FullscreenMode.STANDARD]). It is closed (or minimized to PiP when [forced]) via Esc, handled by
 * [FullscreenOverlayManager].
 */
class FullscreenOverlay(
    val displayScreen: DisplayScreen,
    val mode: FullscreenMode,
    val forced: Boolean = false,
    val sessionId: String? = null,
) {
    @Volatile
    private var frontBuf: ByteBuffer = EMPTY_DIRECT

    private var backBuf: ByteBuffer = EMPTY_DIRECT

    @Volatile
    private var frameW = 0

    @Volatile
    private var frameH = 0

    @Volatile
    private var frameVersion = 0L

    @Volatile
    private var contentAspect = 0.0

    @Volatile
    private var frameFormat = UploadPixelFormat.RGB24

    private var uploadedVersion = 0L

    private var dynamicTexture: DynamicTexture? = null
    private var textureId: Identifier? = null
    private var texW = 0
    private var texH = 0
    private var uploader: AsyncTextureUploader? = null
    private var rgbaUploadBuffer: ByteBuffer? = null

    private var animProgress = 0f
    private var closing = false
    private var lastRenderNanos = 0L

    /** True once the close animation has fully played out; the manager discards the overlay. */
    val isFinished: Boolean get() = closing && animProgress < 0.01f

    /** True while the overlay is fading out. */
    val isClosing: Boolean get() = closing

    /** Stores the latest decoded frame; called off-thread by the popout frame sink. */
    fun updateFrame(
        buf: ByteBuffer,
        w: Int,
        h: Int,
        aspect: Double,
        format: UploadPixelFormat = UploadPixelFormat.RGB24,
    ) {
        val size = w * h * format.bytesPerPixel
        if (size <= 0 || buf.remaining() < size) return
        var back = backBuf
        if (back.capacity() < size) {
            back = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
        back.clear()
        val savedLimit = buf.limit()
        val savedPos = buf.position()
        buf.limit(savedPos + size)
        back.put(buf)
        buf.limit(savedLimit)
        buf.position(savedPos)
        back.flip()

        val prev = frontBuf
        frontBuf = back
        backBuf = if (prev.capacity() >= size) prev else ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        contentAspect = aspect
        frameFormat = format
        frameW = w; frameH = h
        frameVersion++
    }

    /** Uploads the latest stored frame to the GPU texture; render-thread only. */
    fun uploadFrame() {
        val fw = frameW
        val fh = frameH
        val v = frameVersion
        if (v == uploadedVersion) return
        val buf = frontBuf
        val format = frameFormat
        val size = fw * fh * format.bytesPerPixel
        if (fw <= 0 || fh <= 0 || buf.remaining() < size) return

        val mc = Minecraft.getInstance()
        var tex = dynamicTexture
        if (tex == null || texW != fw || texH != fh) {
            tex?.close()
            textureId?.let { mc.textureManager.release(it) }
            val img = NativeImage(NativeImage.Format.RGBA, fw, fh, false)
            //? if >=1.21.11 {
            tex = DynamicTexture({ "dreamdisplays:fullscreen" }, img)
            //?} else
            /*tex = DynamicTexture(img)*/
            textureId = Identifier.fromNamespaceAndPath(
                Initializer.MOD_ID, "fullscreen/${displayScreen.uuid}-${UUID.randomUUID()}"
            )
            mc.textureManager.register(textureId!!, tex)
            dynamicTexture = tex; texW = fw; texH = fh
        }

        TextureUploadUtil.uploadDynamicTexture(
            texture = tex,
            src = buf,
            w = fw,
            h = fh,
            format = format,
            glUploader = { uploader ?: AsyncTextureUploader(stateCache = true).also { uploader = it } },
            rgbaScratch = rgbaUploadBuffer,
            setRgbaScratch = { rgbaUploadBuffer = it },
        )
        uploadedVersion = v
    }

    /** Returns false when the close animation has finished; caller should discard. */
    fun render(
        mc: Minecraft,
        //? if >=26 {
        g: GuiGraphicsExtractor,
        //?} else
        /*g: GuiGraphics,*/
        partialTick: Float,
    ): Boolean {
        val now = System.nanoTime()
        val dt = if (lastRenderNanos == 0L) 0.016f
        else ((now - lastRenderNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastRenderNanos = now

        val target = if (closing) 0f else 1f
        animProgress += (target - animProgress) * minOf(1f, dt * 10f)

        if (isFinished) {
            cleanup(mc); return false
        }

        val sw = mc.window.guiScaledWidth
        val sh = mc.window.guiScaledHeight
        val alpha = animProgress

        // Backdrop: opaque black for immersive, translucent scrim for standard
        val scrimAlpha = if (mode == FullscreenMode.IMMERSIVE) alpha else SCRIM_ALPHA * alpha
        g.fill(0, 0, sw, sh, scaleAlpha(0xFF000000.toInt(), scrimAlpha))

        val id = textureId ?: return true
        val fw = texW
        val fh = texH
        val content = contentRect(fw, fh, contentAspect)
        val aspect = if (content.w > 0 && content.h > 0) content.w / content.h.toDouble() else 16.0 / 9.0

        // Letterbox the video into the available area (full screen or the inset standard area)
        val frac = if (mode == FullscreenMode.STANDARD) STANDARD_FRACTION else 1f
        val availW = (sw * frac).toInt().coerceAtLeast(1)
        val availH = (sh * frac).toInt().coerceAtLeast(1)
        var vw = availW
        var vh = (vw / aspect).toInt().coerceAtLeast(1)
        if (vh > availH) {
            vh = availH
            vw = (vh * aspect).toInt().coerceAtLeast(1)
        }
        val vx = (sw - vw) / 2
        val vy = (sh - vh) / 2

        // Same subtle scale-up-while-fading-in as PipOverlay, so opening/closing reads consistently.
        val scale = 0.94f + 0.06f * animProgress
        val matrices = g.pose()
        //? if >=1.21.11 {
        matrices.pushMatrix()
        matrices.translate(vx + vw / 2f, vy + vh / 2f)
        matrices.scale(scale, scale)
        matrices.translate(-vw / 2f, -vh / 2f)
        //?} else
        /*matrices.pushPose()
        matrices.translate((vx + vw / 2f).toDouble(), (vy + vh / 2f).toDouble(), 0.0)
        matrices.scale(scale, scale, 1f)
        matrices.translate((-vw / 2f).toDouble(), (-vh / 2f).toDouble(), 0.0)*/

        //? if >=1.21.11 {
        g.blit(
            RenderPipelines.GUI_TEXTURED,
            id,
            0,
            0,
            content.x.toFloat(),
            content.y.toFloat(),
            vw,
            vh,
            content.w,
            content.h,
            fw,
            fh,
            scaleAlpha(0xFFFFFFFF.toInt(), alpha),
        )
        //?} else
        /*RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); g.setColor(1f, 1f, 1f, alpha); g.blit(id, 0, 0, vw, vh, content.x.toFloat(), content.y.toFloat(), content.w, content.h, fw, fh); g.setColor(1f, 1f, 1f, 1f)*/

        if (mode == FullscreenMode.STANDARD) {
            g.drawOutline(UiRect(0, 0, vw, vh), scaleAlpha(UiTheme.PANEL_BORDER, alpha))
        }

        //? if >=1.21.11 {
        matrices.popMatrix()
        //?} else
        /*matrices.popPose()*/
        return true
    }

    /** Crops the padded frame texture down to the actual video content area. */
    private fun contentRect(frameW: Int, frameH: Int, contentAspect: Double): ContentRect {
        if (frameW <= 0 || frameH <= 0) return ContentRect(0, 0, frameW, frameH)
        if (contentAspect <= 0.0 || !contentAspect.isFinite()) return ContentRect(0, 0, frameW, frameH)

        val frameAspect = frameW / frameH.toDouble()
        return if (contentAspect > frameAspect) {
            val contentH = (frameW / contentAspect).toInt().coerceIn(1, frameH)
            ContentRect(0, (frameH - contentH) / 2, frameW, contentH)
        } else {
            val contentW = (frameH * contentAspect).toInt().coerceIn(1, frameW)
            ContentRect((frameW - contentW) / 2, 0, contentW, frameH)
        }
    }

    /** Begins the fade-out; the overlay is discarded once the animation finishes. */
    fun startClose() {
        closing = true
    }

    /** Releases the GPU texture and upload resources. */
    fun cleanup(mc: Minecraft) {
        try {
            uploader?.cleanup()
        } catch (_: Exception) {
        }
        uploader = null
        rgbaUploadBuffer = null
        val id = textureId ?: return
        textureId = null
        try {
            mc.textureManager.release(id)
        } catch (_: Exception) {
        }
        dynamicTexture = null
    }

    companion object {
        private val EMPTY_DIRECT: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

        /** Portion of the screen the video area occupies in [FullscreenMode.STANDARD]. */
        private const val STANDARD_FRACTION = 0.92f

        /** Scrim opacity behind the video in [FullscreenMode.STANDARD]. */
        private const val SCRIM_ALPHA = 0.72f
    }

    private data class ContentRect(val x: Int, val y: Int, val w: Int, val h: Int)
}
