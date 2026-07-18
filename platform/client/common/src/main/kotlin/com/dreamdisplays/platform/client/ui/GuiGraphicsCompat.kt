package com.dreamdisplays.platform.client.ui

import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component

//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor

//?} else
/*import net.minecraft.client.gui.GuiGraphics*/

/**
 * Version-neutral alias for the per-frame GUI draw target.
 *
 * Minecraft 26+ draws into a [GuiGraphicsExtractor] (deferred render-state extraction); pre-26 uses
 * the immediate `GuiGraphics`. The two expose the same drawing surface under different method names
 * for some calls (notably text: `text()` vs `drawString()`), which previously forced every overlay /
 * widget / screen to carry two near-identical copies of its render code behind Stonecutter
 * `//? if >=26` gates.
 *
 * This alias plus the small shim layer below lets render bodies be written once against
 * [GuiGraphicsCompat]; only the genuinely divergent call names live here, gated in one place.
 */
//? if >=26 {
typealias GuiGraphicsCompat = GuiGraphicsExtractor
//?} else
/*typealias GuiGraphicsCompat = GuiGraphics*/

/**
 * Draws a single line of [text] with [font] at ([x], [y]) in ARGB [color], optionally with a drop
 * [shadow]. Maps to `text()` on 26+ and `drawString()` on pre-26. The one call that was responsible
 * for the bulk of the duplicated render code.
 */
//? if >=26 {
fun GuiGraphicsCompat.drawText(font: Font, text: String, x: Int, y: Int, color: Int, shadow: Boolean) {
    this.text(font, text, x, y, color, shadow)
}
//?} else
/*fun GuiGraphicsCompat.drawText(font: Font, text: String, x: Int, y: Int, color: Int, shadow: Boolean) {
    this.drawString(font, text, x, y, color, shadow)
}*/

/** [Component] overload of [drawText]: `text()` on 26+, `drawString()` on pre-26. */
//? if >=26 {
fun GuiGraphicsCompat.drawText(font: Font, text: Component, x: Int, y: Int, color: Int, shadow: Boolean) {
    this.text(font, text, x, y, color, shadow)
}
//?} else
/*fun GuiGraphicsCompat.drawText(font: Font, text: Component, x: Int, y: Int, color: Int, shadow: Boolean) {
    this.drawString(font, text, x, y, color, shadow)
}*/
