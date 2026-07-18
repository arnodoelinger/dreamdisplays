package com.dreamdisplays.platform.client.ui

//? if >=1.21.11 {
//?} else
/*import com.mojang.blaze3d.systems.RenderSystem*/
//? if >=26 {
//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
//? if >=1.21.11 {
//?}
//? if >=1.21.11 {
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.overlay.*
import com.dreamdisplays.platform.client.render.AsyncTextureUploader
import com.dreamdisplays.platform.client.render.TextureUploadUtil
import com.dreamdisplays.platform.client.render.UploadPixelFormat
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.drawOutline
import com.dreamdisplays.platform.client.ui.kit.scaleAlpha
import com.dreamdisplays.platform.client.ui.widgets.IconButton
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * In-game Picture-in-Picture overlay for one display screen.
 *
 *  - Click on body (no drag) – `DisplayMenu`
 *  - Click + drag the body – free move; on release snaps smoothly to nearest free anchor
 *  - Click + drag the resize grip – resize (grip is in the corner of the PiP facing screen center)
 *  - Click the close ("cross") button – closes this PiP directly (grip is in the outer corner,
 *    opposite the resize grip)
 */
class PipOverlay(
    val displayScreen: DisplayScreen,
    initialCorner: PipCorner = PipCorner.BOTTOM_RIGHT,
    private val interactive: Boolean = true,
    initialSizeFraction: Float = 0.25f,
) : Overlay {
    @Volatile
    private var frontBuf: ByteBuffer = EMPTY_DIRECT

    private var backBuf: ByteBuffer = EMPTY_DIRECT

    @Volatile
    var frameW = 0

    @Volatile
    var frameH = 0

    @Volatile
    private var frameVersion = 0L

    @Volatile
    private var contentAspect = 0.0

    @Volatile
    private var frameFormat = UploadPixelFormat.RGB24

    private var uploadedVersion = 0L

    private var dynamicTexture: DynamicTexture? = null
    private var textureId: Identifier? = null
    private var texW = 0;
    private var texH = 0
    private var uploader: AsyncTextureUploader? = null
    private var rgbaUploadBuffer: ByteBuffer? = null

    var anchor: PipAnchor = PipAnchor.fromCorner(initialCorner)
    private var sizeFraction: Float = initialSizeFraction.coerceIn(MIN_SIZE_FRAC, MAX_SIZE_FRAC)

    private var posX: Float = 0f
    private var posY: Float = 0f
    private var targetX: Float = 0f
    private var targetY: Float = 0f
    private var posInitialized = false

    var lastPipX = 0; private set
    var lastPipY = 0; private set
    var lastPipW = 0; private set
    var lastPipH = 0; private set

    private var animProgress = 0f
    private var closing = false
    private var lastRenderNanos = 0L

    private var wasLeftPressed = false
    private var pressed = false
    private var pressedInBody = false
    private var pressedInResize = false
    private var pressedInClose = false
    private var dragging = false
    private var resizing = false
    private var pressMouseX = 0
    private var pressMouseY = 0
    private var dragOffsetX = 0
    private var dragOffsetY = 0
    private var resizeStartFrac = 0f

    private var hovering = false
    private var hoveringResize = false
    private var hoveringClose = false

    val isFinished: Boolean get() = closing && animProgress < 0.01f
    val isDragging: Boolean get() = dragging

    /** Stable identity usable with [Overlay] / [OverlayManager] contracts. */
    override val displayId: DisplayId get() = DisplayId(displayScreen.uuid)

    /** Visible until the close animation has fully played out. */
    override val isVisible: Boolean get() = !isFinished

    /** Current screen-space bounds, valid only after the first [render] call. */
    override val bounds: OverlayBounds
        get() = OverlayBounds(
            x = lastPipX.toFloat(),
            y = lastPipY.toFloat(),
            width = lastPipW.toFloat(),
            height = lastPipH.toFloat(),
        )

    /**
     * [Overlay] contract entry point. Bridges the platform-agnostic call into the existing
     * Minecraft render path by unwrapping a [MinecraftOverlayRenderContext]; a context of any other
     * type is ignored (this overlay can only draw through Minecraft's HUD).
     */
    override fun render(context: OverlayRenderContext) {
        val mc = context as? MinecraftOverlayRenderContext ?: return
        render(mc.mc, mc.graphics, mc.mouseX, mc.mouseY, mc.leftPressed, context.partialTick)
    }

    /**
     * [Overlay] contract input hook. PiP input is otherwise polled from the render context
     * (see [handleMouseInput]); this only handles the explicit close request.
     * @return true if the event was consumed.
     */
    override fun onEvent(event: OverlayEvent): Boolean = when (event) {
        is OverlayEvent.CloseRequested -> {
            startClose(); true
        }

        else -> false
    }

    fun updateFrame(
        buf: ByteBuffer,
        w: Int,
        h: Int,
        aspect: Double,
        format: UploadPixelFormat = UploadPixelFormat.RGB24
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

    fun uploadFrame() {
        val fw = frameW;
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
            tex = DynamicTexture({ "dreamdisplays:pip" }, img)
            //?} else
            /*tex = DynamicTexture(img)*/
            textureId = Identifier.fromNamespaceAndPath(
                Initializer.MOD_ID, "pip/${displayScreen.uuid}-${UUID.randomUUID()}"
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

    /** Returns false when the close animation has finished – caller should discard. */
    fun render(
        mc: Minecraft,
        //? if >=26 {
        g: GuiGraphicsExtractor,
        //?} else
        /*g: GuiGraphics,*/
        mouseX: Int, mouseY: Int,
        leftPressed: Boolean,
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
        val id = textureId ?: return true

        val sw = mc.window.guiScaledWidth
        val sh = mc.window.guiScaledHeight
        val fw = texW;
        val fh = texH
        val content = contentRect(fw, fh, contentAspect)
        val contentAspect = if (content.w > 0 && content.h > 0) content.w / content.h.toDouble() else 16.0 / 9.0
        val pipW = (sw * sizeFraction).toInt().coerceAtLeast(80)
        val pipH = (pipW / contentAspect).toInt().coerceAtLeast(45)

        if (!posInitialized) {
            val (ax, ay) = anchor.position(sw, sh, pipW, pipH, MARGIN)
            posX = ax.toFloat(); posY = ay.toFloat()
            targetX = posX; targetY = posY
            posInitialized = true
        }

        handleMouseInput(mouseX, mouseY, leftPressed, sw, sh, pipW, pipH)

        if (!dragging && !resizing) {
            val lerp = minOf(1f, dt * SNAP_LERP_SPEED)
            posX += (targetX - posX) * lerp
            posY += (targetY - posY) * lerp
        } else if (resizing) {
            val (ax, ay) = anchor.position(sw, sh, pipW, pipH, MARGIN)
            posX = ax.toFloat(); posY = ay.toFloat()
            targetX = posX; targetY = posY
        }

        val cx = posX.toInt();
        val cy = posY.toInt()
        lastPipX = cx; lastPipY = cy; lastPipW = pipW; lastPipH = pipH

        val (handleX, handleY) = handlePixelPos(pipW, pipH)
        val (closeX, closeY) = closeButtonPixelPos(pipW, pipH)

        hovering = mouseX in cx..(cx + pipW) && mouseY in cy..(cy + pipH) && animProgress > 0.6f
        hoveringResize = hovering &&
                mouseX in (cx + handleX)..(cx + handleX + RESIZE_SZ) &&
                mouseY in (cy + handleY)..(cy + handleY + RESIZE_SZ)
        hoveringClose = hovering &&
                mouseX in (cx + closeX)..(cx + closeX + CLOSE_SZ) &&
                mouseY in (cy + closeY)..(cy + closeY + CLOSE_SZ)

        val scale = 0.94f + 0.06f * animProgress
        val alpha = animProgress

        val matrices = g.pose()
        //? if >=1.21.11 {
        matrices.pushMatrix()
        matrices.translate(cx + pipW / 2f, cy + pipH / 2f)
        matrices.scale(scale, scale)
        matrices.translate(-pipW / 2f, -pipH / 2f)
        //?} else
        /*matrices.pushPose()
        matrices.translate((cx + pipW / 2f).toDouble(), (cy + pipH / 2f).toDouble(), PIP_Z)
        matrices.scale(scale, scale, 1f)
        matrices.translate((-pipW / 2f).toDouble(), (-pipH / 2f).toDouble(), 0.0)*/

        // Video content only. The main display texture is padded to fit the in-world display.
        // On 1.21.1 the legacy blit draws with the POSITION_TEX shader (alpha comes from the shader
        // color) but does not enable blending, so the fade alpha is invisible without enableBlend.
        // 1.21.11+ uses a render pipeline that already blends, and bakes alpha into the blit color.
        //? if >=1.21.11 {
        g.blit(
            RenderPipelines.GUI_TEXTURED,
            id,
            0,
            0,
            content.x.toFloat(),
            content.y.toFloat(),
            pipW,
            pipH,
            content.w,
            content.h,
            fw,
            fh,
            scaleAlpha(0xFFFFFFFF.toInt(), alpha),
        )
        //?} else
        /*RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); g.setColor(1f, 1f, 1f, alpha); g.blit(id, 0, 0, pipW, pipH, content.x.toFloat(), content.y.toFloat(), content.w, content.h, fw, fh); g.setColor(1f, 1f, 1f, 1f)*/

        // Border
        val active = hovering || dragging || resizing
        val borderColor = scaleAlpha(if (active) UiTheme.ACCENT else UiTheme.PANEL_BORDER, alpha)
        g.drawOutline(UiRect(0, 0, pipW, pipH), borderColor)

        if (hovering || resizing) {
            renderResizeHandle(g, handleX, handleY, alpha)
        }
        if (hovering) {
            renderCloseButton(g, closeX, closeY, alpha)
        }

        //? if >=1.21.11 {
        matrices.popMatrix()
        //?} else
        /*matrices.popPose()*/
        return true
    }

    private fun handleMouseInput(
        mx: Int, my: Int, leftPressed: Boolean,
        sw: Int, sh: Int, pipW: Int, pipH: Int,
    ) {
        val cx = posX.toInt();
        val cy = posY.toInt()
        val pressJustDown = leftPressed && !wasLeftPressed
        val pressJustUp = !leftPressed && wasLeftPressed

        if (pressJustDown) {
            val inBody = mx in cx..(cx + pipW) && my in cy..(cy + pipH)
            if (inBody && animProgress > 0.6f) {
                pressed = true
                pressMouseX = mx; pressMouseY = my
                val (hx, hy) = handlePixelPos(pipW, pipH)
                val (clx, cly) = closeButtonPixelPos(pipW, pipH)
                pressedInResize = mx in (cx + hx)..(cx + hx + RESIZE_SZ) &&
                        my in (cy + hy)..(cy + hy + RESIZE_SZ)
                pressedInClose = !pressedInResize &&
                        mx in (cx + clx)..(cx + clx + CLOSE_SZ) &&
                        my in (cy + cly)..(cy + cly + CLOSE_SZ)
                pressedInBody = !pressedInResize && !pressedInClose
                dragOffsetX = mx - cx
                dragOffsetY = my - cy
                resizeStartFrac = sizeFraction
            }
        }

        if (pressed && leftPressed) {
            val dx = mx - pressMouseX
            val dy = my - pressMouseY
            val dist2 = dx * dx + dy * dy
            if (pressedInResize) {
                if (!resizing && dist2 > DRAG_THRESHOLD * DRAG_THRESHOLD) resizing = true
                if (resizing) {
                    val (sx, sy) = anchor.centerFacingCorner()
                    val grow = sx * dx + sy * dy
                    val activeAxes = (if (sx != 0) 1 else 0) + (if (sy != 0) 1 else 0)
                    val denom = activeAxes.coerceAtLeast(1) * sw.toFloat()
                    sizeFraction = (resizeStartFrac + grow / denom).coerceIn(MIN_SIZE_FRAC, MAX_SIZE_FRAC)
                }
            } else if (pressedInBody) {
                if (!dragging && dist2 > DRAG_THRESHOLD * DRAG_THRESHOLD) dragging = true
                if (dragging) {
                    posX = (mx - dragOffsetX).toFloat().coerceIn(-pipW * 0.5f, sw - pipW * 0.5f)
                    posY = (my - dragOffsetY).toFloat().coerceIn(-pipH * 0.5f, sh - pipH * 0.5f)
                    targetX = posX; targetY = posY
                }
            }
        }

        if (pressJustUp && pressed) {
            when {
                dragging -> {
                    val centerX = posX + pipW / 2f
                    val centerY = posY + pipH / 2f
                    anchor = findNearestFreeAnchor(sw, sh, pipW, pipH, centerX, centerY)
                    val (ax, ay) = anchor.position(sw, sh, pipW, pipH, MARGIN)
                    targetX = ax.toFloat(); targetY = ay.toFloat()
                    dragging = false
                }

                resizing -> resizing = false
                pressedInClose -> {
                    val s = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f)
                    Minecraft.getInstance().soundManager.play(s)
                    displayScreen.deactivatePopout()
                }
                pressedInBody -> if (interactive) DisplayMenu.open(displayScreen)
            }
            pressed = false
            pressedInBody = false
            pressedInResize = false
            pressedInClose = false
        }

        wasLeftPressed = leftPressed
    }

    private fun findNearestFreeAnchor(sw: Int, sh: Int, pw: Int, ph: Int, cx: Float, cy: Float): PipAnchor {
        var best = anchor
        var bestDist = Float.MAX_VALUE
        for (a in PipAnchor.entries) {
            if (!PipOverlayManager.canUseAnchor(this, a)) continue
            val (ax, ay) = a.position(sw, sh, pw, ph, MARGIN)
            val anchorCx = ax + pw / 2f
            val anchorCy = ay + ph / 2f
            val ddx = anchorCx - cx
            val ddy = anchorCy - cy
            val d2 = ddx * ddx + ddy * ddy
            if (d2 < bestDist) {
                bestDist = d2; best = a
            }
        }
        return best
    }

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

    /** Returns the (x, y) top-left position of the resize handle in PiP-local coords. */
    private fun handlePixelPos(pipW: Int, pipH: Int): Pair<Int, Int> {
        val (sx, sy) = anchor.centerFacingCorner()
        val x = when {
            sx > 0 -> pipW - RESIZE_SZ - RESIZE_INSET
            sx < 0 -> RESIZE_INSET
            else -> (pipW - RESIZE_SZ) / 2
        }
        val y = when {
            sy > 0 -> pipH - RESIZE_SZ - RESIZE_INSET
            sy < 0 -> RESIZE_INSET
            else -> (pipH - RESIZE_SZ) / 2
        }
        return x to y
    }

    /** Returns the (x, y) top-left position of the close button, in the corner opposite the resize handle. */
    private fun closeButtonPixelPos(pipW: Int, pipH: Int): Pair<Int, Int> {
        val (cfx, cfy) = anchor.centerFacingCorner()
        val sx = -cfx
        val sy = -cfy
        val x = when {
            sx > 0 -> pipW - CLOSE_SZ - RESIZE_INSET
            sx < 0 -> RESIZE_INSET
            else -> (pipW - CLOSE_SZ) / 2
        }
        val y = when {
            sy > 0 -> pipH - CLOSE_SZ - RESIZE_INSET
            sy < 0 -> RESIZE_INSET
            else -> (pipH - CLOSE_SZ) / 2
        }
        return x to y
    }

    /** Draws the close ("cross") icon at ([hx], [hy]), tinted red on hover. */
    private fun renderCloseButton(g: GuiGraphicsCompat, hx: Int, hy: Int, alpha: Float) {
        val tint = scaleAlpha(if (hoveringClose) UiTheme.ACCENT_UPDATE else 0xFFFFFFFF.toInt(), alpha)
        val margin = 2
        //? if >=1.21.11 {
        g.blitSprite(
            RenderPipelines.GUI_TEXTURED, IconButton.modIcon("cross"),
            hx + margin, hy + margin, CLOSE_SZ - margin * 2, CLOSE_SZ - margin * 2, tint,
        )
        //?} else
        /*g.blitSprite(IconButton.modIcon("cross"), hx + margin, hy + margin, CLOSE_SZ - margin * 2, CLOSE_SZ - margin * 2)*/
    }

    private fun renderResizeHandle(g: GuiGraphicsCompat, hx: Int, hy: Int, alpha: Float) {
        val (sx, sy) = anchor.centerFacingCorner()
        val color = scaleAlpha(if (hoveringResize) UiTheme.ACCENT else 0xFFFFFFFF.toInt(), alpha)
        drawCornerBracket(g, hx, hy, sx, sy, 8, 0, color)
        drawCornerBracket(g, hx, hy, sx, sy, 5, 4, color)
    }

    private fun drawCornerBracket(
        g: GuiGraphicsCompat,
        baseX: Int, baseY: Int,
        sx: Int, sy: Int,
        len: Int,
        inset: Int,
        color: Int,
    ) {
        if (sx != 0 && sy != 0) {
            val ax = if (sx < 0) baseX + inset else baseX + RESIZE_SZ - 1 - inset
            val ay = if (sy < 0) baseY + inset else baseY + RESIZE_SZ - 1 - inset
            val xs = if (sx < 0) ax else ax - len + 1
            val xe = xs + len
            g.fill(xs, ay, xe, ay + 1, color)
            val ys = if (sy < 0) ay else ay - len + 1
            val ye = ys + len
            g.fill(ax, ys, ax + 1, ye, color)
        } else if (sx != 0) {
            val ax = if (sx < 0) baseX + inset else baseX + RESIZE_SZ - 1 - inset
            val cy = baseY + RESIZE_SZ / 2
            g.fill(ax, cy - len / 2, ax + 1, cy + (len + 1) / 2, color)
        } else if (sy != 0) {
            val ay = if (sy < 0) baseY + inset else baseY + RESIZE_SZ - 1 - inset
            val cx = baseX + RESIZE_SZ / 2
            g.fill(cx - len / 2, ay, cx + (len + 1) / 2, ay + 1, color)
        }
    }

    fun startClose() {
        closing = true
    }

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

        private const val MARGIN = 12
        private const val DRAG_THRESHOLD = 4
        private const val MIN_SIZE_FRAC = 0.12f
        private const val MAX_SIZE_FRAC = 0.6f
        private const val RESIZE_SZ = 14
        private const val RESIZE_INSET = 6
        private const val CLOSE_SZ = 14
        private const val SNAP_LERP_SPEED = 8f
        private const val PIP_Z = 1_000.0
    }

    private data class ContentRect(val x: Int, val y: Int, val w: Int, val h: Int)
}
