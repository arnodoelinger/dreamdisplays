package com.dreamdisplays.client.ui.widgets

import net.minecraft.client.Minecraft
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.WidgetSprites
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import kotlin.math.max

/** Button widget. **/
// TODO: rewrite this class entirely in 1.9.0
abstract class ButtonWidget(
    x: Int, y: Int, width: Int, height: Int,
    private val iconWidth: Int,
    private val iconHeight: Int,
    private var iconTextureId: Identifier,
    private val margin: Int,
) : AbstractWidget(x, y, width, height, Component.empty()) {

    private var setSprites: WidgetSprites? = null

    /** Replaces the icon texture with [id]. */
    fun setIconTextureId(id: Identifier) {
        iconTextureId = id
    }

    /** Overrides the default button sprites with [sprites]. */
    fun setSprites(sprites: WidgetSprites) {
        setSprites = sprites
    }

    abstract fun onPress()

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        onPress()
        super.playDownSound(Minecraft.getInstance().soundManager)
    }

    override fun updateWidgetNarration(builder: NarrationElementOutput) {}

    //? if >=26 {
    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
    //?} else
    /*override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {*/
        val sprite = setSprites?.get(active, isHoveredOrFocused) ?: SPRITES.get(active, isHoveredOrFocused)
        g.blitSprite(
            RenderPipelines.GUI_TEXTURED, sprite,
            x, y, width, height, ARGB.white(alpha)
        )

        val dW = width - 2 * margin
        val dH = height - 2 * margin

        var iconW = dW
        val iconH = max((iconHeight.toDouble() / iconWidth) * iconW, dH.toDouble()).toInt()
        iconW = ((iconWidth.toDouble() / iconHeight) * iconH).toInt()

        val dx = x + width / 2 - iconW / 2
        val dy = y + height / 2 - iconH / 2

        g.blitSprite(
            RenderPipelines.GUI_TEXTURED, iconTextureId,
            dx, dy, iconW, iconH, ARGB.white(alpha)
        )
    }

    companion object {
        private val SPRITES = WidgetSprites(
            Identifier.withDefaultNamespace("widget/button"),
            Identifier.withDefaultNamespace("widget/button_disabled"),
            Identifier.withDefaultNamespace("widget/button_highlighted"),
        )
    }
}
