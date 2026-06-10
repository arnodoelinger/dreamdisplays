package com.dreamdisplays.client.ui.kit

import com.dreamdisplays.client.ui.GuiGraphicsCompat
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Base class for Dream Displays screens.
 *
 * Runs the declarative [UiWidget.syncState] pass for every registered widget before each frame so
 * screens never imperatively toggle `active` / `visible` flags.
 *
 * @since 1.8.0
 */
abstract class UiScreenBase(title: Component) : Screen(title) {
    private val uiWidgets = ArrayList<UiWidget>()

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

    /** Version-neutral render body: draw panels and custom content here, then call [drawChildren]. */
    protected abstract fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float)

    //? if >=26 {
    final override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        uiWidgets.forEach { it.syncState() }
        drawScreen(g, mouseX, mouseY, delta)
    }

    /** Draws the standard translucent screen background. */
    protected fun drawScreenBackground(g: GuiGraphicsCompat) = extractTransparentBackground(g)

    /** Renders all registered child widgets (the vanilla `super` pass). */
    protected fun drawChildren(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, delta: Float) =
        super.extractRenderState(g, mouseX, mouseY, delta)
    //?} else
    /*final override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        uiWidgets.forEach { it.syncState() }
        drawScreen(g, mouseX, mouseY, delta)
    }

    // Draws the standard translucent screen background.
    protected fun drawScreenBackground(g: GuiGraphicsCompat) = renderTransparentBackground(g)

    // Renders all registered child widgets (the vanilla `super` pass).
    protected fun drawChildren(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, delta: Float) =
        super.render(g, mouseX, mouseY, delta)*/
}
