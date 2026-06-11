package com.dreamdisplays.client.ui.menu

import com.dreamdisplays.client.ui.GuiGraphicsCompat
import com.dreamdisplays.client.ui.drawText
import com.dreamdisplays.client.ui.kit.UiRect
import com.dreamdisplays.client.ui.kit.UiTheme
import com.dreamdisplays.client.ui.kit.drawPanel
import com.dreamdisplays.client.ui.widgets.IconButton
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import kotlin.math.min

/**
 * The centered "video failed to load" panel shown instead of the normal menu content: error text
 * plus the delete and report buttons relocated into the panel's bottom edge.
 *
 * @since 1.0.0
 */
class ErrorPanel(
    private val deleteButton: IconButton,
    private val reportButton: IconButton?,
) {
    /** Draws the error panel centered in a [screenW] x [screenH] screen and places the action buttons. */
    fun render(g: GuiGraphicsCompat, screenW: Int, screenH: Int) {
        val font = Minecraft.getInstance().font
        val panelW = min(420, screenW - 40)
        val panel = UiRect(screenW / 2 - panelW / 2, screenH / 2 - 70, panelW, PANEL_H)
        g.drawPanel(font, panel, Component.translatable("dreamdisplays.ui.error").string)

        val lines = listOf(
            Component.translatable("dreamdisplays.error.loadingerror.1").withStyle { it.withColor(ChatFormatting.RED) },
            Component.translatable("dreamdisplays.error.loadingerror.2").withStyle { it.withColor(ChatFormatting.RED) },
            Component.translatable("dreamdisplays.error.loadingerror.4").withStyle { it.withColor(ChatFormatting.GRAY) },
            Component.translatable("dreamdisplays.error.loadingerror.5").withStyle { it.withColor(ChatFormatting.GRAY) },
        )
        var y = panel.y + UiTheme.PANEL_PADDING_Y + font.lineHeight + 6 + 8
        for (line in lines) {
            g.drawText(font, line, screenW / 2 - font.width(line) / 2, y, UiTheme.TEXT_PRIMARY, false)
            y += font.lineHeight + 4
        }

        deleteButton.place(UiRect(panel.centerX - 22, panel.bottom - 24, 20, 20))
        reportButton?.place(UiRect(panel.centerX + 2, panel.bottom - 24, 20, 20))
    }

    companion object {
        private const val PANEL_H = 130
    }
}
