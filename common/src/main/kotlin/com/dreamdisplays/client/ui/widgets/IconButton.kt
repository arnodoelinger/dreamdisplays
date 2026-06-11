package com.dreamdisplays.client.ui.widgets

import com.dreamdisplays.Initializer
import com.dreamdisplays.client.ui.GuiGraphicsCompat
import com.dreamdisplays.client.ui.kit.UiWidget
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.WidgetSprites
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import kotlin.math.max

/**
 * Vanilla-styled button with a centered mod icon. The icon is a per-frame lambda so stateful buttons
 * (play / pause, mute, lock) re-skin themselves declaratively instead of call sites swapping textures.
 *
 * @param icon resolves the current icon sprite each frame.
 * @param sprites button background sprites; defaults to the vanilla grey button set.
 * @param margin inner padding between the button edge and the icon.
 * @param onPress click action.
 *
 * @since 1.0.0
 */
class IconButton(
    private val icon: () -> Identifier,
    private val sprites: WidgetSprites = DEFAULT_SPRITES,
    private val margin: Int = 2,
    private val onPress: () -> Unit,
) : UiWidget(Component.empty()) {

    constructor(icon: String, onPress: () -> Unit) :
            this(icon = { modIcon(icon) }, onPress = onPress)

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        onPress()
        playDownSound(Minecraft.getInstance().soundManager)
    }

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        val sprite = sprites.get(active, isHoveredOrFocused)
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height, ARGB.white(alpha))

        // All mod icons are square; size to the larger inner dimension, centered (matches old behavior).
        val iconSide = max(width - 2 * margin, height - 2 * margin)
        val dx = x + width / 2 - iconSide / 2
        val dy = y + height / 2 - iconSide / 2
        g.blitSprite(RenderPipelines.GUI_TEXTURED, icon(), dx, dy, iconSide, iconSide, ARGB.white(alpha))
    }

    companion object {
        val DEFAULT_SPRITES = WidgetSprites(
            Identifier.withDefaultNamespace("widget/button"),
            Identifier.withDefaultNamespace("widget/button_disabled"),
            Identifier.withDefaultNamespace("widget/button_highlighted"),
        )

        /** Red variant used for destructive actions (delete, report). */
        val RED_SPRITES = WidgetSprites(
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "widgets/red_button"),
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "widgets/red_button_disabled"),
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "widgets/red_button_highlighted"),
        )

        /** Resolves a mod-namespaced icon sprite by [name]. */
        fun modIcon(name: String): Identifier = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, name)
    }
}
