package com.dreamdisplays.platform.client.ui.kit

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * Base class for all Dream Displays widgets.
 *
 * State is declarative: [enabledWhen] / [visibleWhen] are re-evaluated once per frame by the owning
 * [UiScreenBase] (via [syncState]) instead of call sites imperatively flipping `active` / `visible` flags.
 */
abstract class UiWidget(message: Component) : AbstractWidget(0, 0, 0, 0, message) {
    /** Re-evaluated each frame to drive [active]; widgets reject input while disabled. */
    var enabledWhen: () -> Boolean = { true }

    /** Re-evaluated each frame to drive [visible]; invisible widgets are skipped entirely. */
    var visibleWhen: () -> Boolean = { true }

    /** Moves and resizes this widget to [r]; layout code calls this once per frame. */
    fun place(r: UiRect) {
        x = r.x
        y = r.y
        width = r.w
        height = r.h
    }

    /** Pulls [enabledWhen]/[visibleWhen] into the vanilla `active`/`visible` flags. */
    fun syncState() {
        active = enabledWhen()
        visible = visibleWhen()
    }

    /** Version-neutral render body; implement all drawing here. */
    protected abstract fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float)

    /** True for simple controls; composite widgets should request cursors only for their interactive parts. */
    protected open fun handlesWholeWidgetCursor(): Boolean = true

    //? if >=26 {
    /** Requests the cursor for this widget's hovered state. Override for controls with special cursors. */
    protected open fun requestWidgetCursor(g: GuiGraphicsExtractor) = handleCursor(g)

    final override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        draw(g, mouseX, mouseY, partialTick)
        if (handlesWholeWidgetCursor()) requestWidgetCursor(g)
    }

    /** Draws [text] centered over the widget with vanilla scrolling-on-overflow behavior. */
    protected fun drawScrollingLabel(g: GuiGraphicsCompat, text: Component, padding: Int) =
        extractScrollingStringOverContents(
            g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.TOOLTIP_AND_CURSOR),
            text, padding,
        )
    //?} else
    /*final override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) =
        draw(g, mouseX, mouseY, partialTick)

    // Draws [text] centered over the widget with vanilla scrolling-on-overflow behavior.
    protected fun drawScrollingLabel(g: GuiGraphicsCompat, text: Component, padding: Int) =
        renderScrollingStringOverContents(
            g.textRendererForWidget(this, GuiGraphics.HoveredTextEffects.TOOLTIP_AND_CURSOR),
            text, padding,
        )*/

    override fun updateWidgetNarration(builder: NarrationElementOutput) {}
}
