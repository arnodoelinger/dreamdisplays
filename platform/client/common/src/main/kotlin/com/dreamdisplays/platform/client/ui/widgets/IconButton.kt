package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.kit.UiWidget
//? if >=1.21.11 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.WidgetSprites
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
//?}
import net.minecraft.network.chat.Component
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
//? if >=1.21.11 {
import net.minecraft.util.ARGB
//?}
import kotlin.math.max

/**
 * Vanilla-styled button with a centered mod icon. The icon is a per-frame lambda so stateful buttons
 * (play / pause, mute, lock) re-skin themselves declaratively instead of call sites swapping textures.
 *
 * @param icon resolves the current icon sprite each frame.
 * @param sprites button background sprites; defaults to the vanilla grey button set.
 * @param margin inner padding between the button edge and the icon.
 * @param onPress click action.
 */
class IconButton(
    private val icon: () -> Identifier,
    private val sprites: WidgetSprites = DEFAULT_SPRITES,
    private val margin: Int = 2,
    private val onPress: () -> Unit,
) : UiWidget(Component.empty()) {

    constructor(icon: String, onPress: () -> Unit) :
            this(icon = { modIcon(icon) }, onPress = onPress)

    override fun handlesWholeWidgetCursor(): Boolean = false

    // NeoForge deprecates the 2-arg onClick and reroutes mouseClicked to a Neo-only 3-arg overload that
    // Fabric lacks, so the legacy (1.21.1) branch overrides mouseClicked itself — the one click hook
    // that fires on both platforms.
    //? if >=1.21.11 {
    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        onPress()
        playDownSound(Minecraft.getInstance().soundManager)
    }
    //?} else
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!isValidClickButton(button) || !clicked(mouseX, mouseY)) return false
        onPress()
        playDownSound(Minecraft.getInstance().soundManager)
        return true
    }*/

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (width <= 0 || height <= 0) return
        val sprite = sprites.get(active, isHoveredOrFocused)
        //? if >=1.21.11 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height, ARGB.white(alpha))
        //?} else
        /*g.blitSprite(sprite, x, y, width, height)*/

        g.enableScissor(x, y, x + width, y + height)
        val iconSide = max(width - 2 * margin, height - 2 * margin)
        val dx = x + width / 2 - iconSide / 2
        val dy = y + height / 2 - iconSide / 2
        //? if >=1.21.11 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, icon(), dx, dy, iconSide, iconSide, ARGB.white(alpha))
        //?} else
        /*g.blitSprite(icon(), dx, dy, iconSide, iconSide)*/
        g.disableScissor()
        //? if >=1.21.11 {
        if (isHovered) {
            g.requestCursor(if (active) CursorTypes.POINTING_HAND else CursorTypes.NOT_ALLOWED)
        }
        //?}
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
