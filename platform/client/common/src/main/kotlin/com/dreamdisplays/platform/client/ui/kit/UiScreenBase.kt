package com.dreamdisplays.platform.client.ui.kit

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
import net.minecraft.client.gui.screens.Screen
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent
//?}
import net.minecraft.network.chat.Component

/**
 * Base class for Dream Displays screens. It handles the per-frame state sync, operates in virtual
 * coordinates, and handles scaling to fit the real window.
 */
abstract class UiScreenBase(title: Component) : Screen(title) {
    private val uiWidgets = ArrayList<UiWidget>()

    /**
     * Current downscale factor in (0, 1]; 1.0 means the window is large enough and nothing is scaled.
     * Mouse coordinates are divided by this to map real -> virtual space.
     */
    protected var uiScale: Double = 1.0
        private set

    /** Registers [w] as a renderable, interactive child and includes it in the per-frame state sync. */
    protected fun <T : UiWidget> addUi(w: T): T {
        uiWidgets.add(w)
        addRenderableWidget(w)
        return w
    }

    override fun init() {
        uiWidgets.clear()
        super.init()
    }

    /**
     * The minimum logical canvas this screen wants to lay out against, or null to never scale. When the
     * real window is smaller than this on either axis, the screen is rendered on a virtual canvas of at
     * least this size and scaled down to fit.
     */
    protected open fun minContentSize(): Pair<Int, Int>? = null

    /** Version-neutral render body: draw panels and custom content here, then call [drawChildren]. */
    protected abstract fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float)

    /** Maps a virtual X coordinate back to a real screen X (e.g. for tooltips, which render unscaled). */
    protected fun toRealX(virtualX: Int): Int = (virtualX * uiScale).toInt()

    /** Maps a virtual Y coordinate back to a real screen Y (e.g. for tooltips, which render unscaled). */
    protected fun toRealY(virtualY: Int): Int = (virtualY * uiScale).toInt()

    /** Computes the downscale factor needed to fit [minContentSize] into the current real window. */
    private fun computeScale(): Double {
        val (mw, mh) = minContentSize() ?: return 1.0
        if (mw <= 0 || mh <= 0) return 1.0
        return minOf(1.0, width.toDouble() / mw, height.toDouble() / mh)
    }

    /**
     * Runs the per-frame state sync and renders [drawScreen], applying the auto-scale transform when the
     * window is too small. While scaled, [width]/[height] hold the virtual canvas size so all
     * screen-relative layout and the background fill map to the full real window.
     */
    private fun renderScaled(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, delta: Float) {
        uiScale = computeScale()
        uiWidgets.forEach { it.syncState() }

        if (uiScale >= 1.0) {
            drawScreen(g, mouseX, mouseY, delta)
            return
        }

        val s = uiScale
        val realW = width
        val realH = height
        width = (realW / s).toInt()
        height = (realH / s).toInt()

        val matrices = g.pose()
        //? if >=1.21.11 {
        matrices.pushMatrix()
        matrices.scale(s.toFloat(), s.toFloat())
        drawScreen(g, (mouseX / s).toInt(), (mouseY / s).toInt(), delta)
        matrices.popMatrix()
        //?} else
        /*matrices.pushPose()
        matrices.scale(s.toFloat(), s.toFloat(), 1f)
        drawScreen(g, (mouseX / s).toInt(), (mouseY / s).toInt(), delta)
        matrices.popPose()*/

        width = realW
        height = realH
    }

    //? if >=1.21.11 {
    /** Converts a real-space mouse event into virtual space; returns it unchanged when not scaling. */
    private fun toVirtual(event: MouseButtonEvent): MouseButtonEvent =
        if (uiScale >= 1.0) event
        else MouseButtonEvent(event.x() / uiScale, event.y() / uiScale, event.buttonInfo())

    /** Screen-specific click handling, in virtual coordinates. Return true to consume. */
    protected open fun onMouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean = false

    /** Screen-specific release handling, in virtual coordinates. Return true to consume. */
    protected open fun onMouseReleased(event: MouseButtonEvent): Boolean = false

    final override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val e = toVirtual(event)
        return onMouseClicked(e, doubleClick) || super.mouseClicked(e, doubleClick)
    }

    final override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val e = toVirtual(event)
        return onMouseReleased(e) || super.mouseReleased(e)
    }

    /** Screen-specific drag handling, in virtual coordinates. Return true to consume. */
    protected open fun onMouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean = false

    final override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val e = toVirtual(event)
        val dx = dragX / uiScale
        val dy = dragY / uiScale
        return onMouseDragged(e, dx, dy) || super.mouseDragged(e, dx, dy)
    }
    //?} else
    /*protected open fun onMouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean = false

    protected open fun onMouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean = false

    final override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mx = mouseX / uiScale
        val my = mouseY / uiScale
        return onMouseClicked(mx, my, button) || super.mouseClicked(mx, my, button)
    }

    final override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mx = mouseX / uiScale
        val my = mouseY / uiScale
        return onMouseReleased(mx, my, button) || super.mouseReleased(mx, my, button)
    }

    protected open fun onMouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean = false

    final override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        val mx = mouseX / uiScale
        val my = mouseY / uiScale
        return onMouseDragged(mx, my, button, dragX / uiScale, dragY / uiScale) ||
            super.mouseDragged(mx, my, button, dragX / uiScale, dragY / uiScale)
    }*/

    /** Screen-specific scroll handling, in virtual coordinates. Return true to consume. */
    protected open fun onMouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean = false

    final override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val mx = mouseX / uiScale
        val my = mouseY / uiScale
        return onMouseScrolled(mx, my, scrollX, scrollY) || super.mouseScrolled(mx, my, scrollX, scrollY)
    }

    final override fun mouseMoved(mouseX: Double, mouseY: Double) =
        super.mouseMoved(mouseX / uiScale, mouseY / uiScale)

    //? if >=26 {
    final override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) =
        renderScaled(g, mouseX, mouseY, delta)

    /** Draws the standard translucent screen background. */
    protected fun drawScreenBackground(g: GuiGraphicsCompat) = extractTransparentBackground(g)

    /** Renders all registered child widgets (the vanilla `super` pass). */
    protected fun drawChildren(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, delta: Float) =
        super.extractRenderState(g, mouseX, mouseY, delta)
    //?} else
    /*final override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) =
        renderScaled(g, mouseX, mouseY, delta)

    // Draws the standard translucent screen background.
    protected fun drawScreenBackground(g: GuiGraphicsCompat) = renderTransparentBackground(g)

    // Renders all registered child widgets (the vanilla `super` pass).
    protected fun drawChildren(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, delta: Float) =
        super.render(g, mouseX, mouseY, delta)

    //? if <1.21.11 {
    // 1.21.1 strange background remover
    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {}
    //?}
    */
}
