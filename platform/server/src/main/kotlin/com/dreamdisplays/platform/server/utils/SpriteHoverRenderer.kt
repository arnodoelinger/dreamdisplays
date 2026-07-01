package com.dreamdisplays.platform.server.utils

import io.github.arnodoelinger.platformweaver.PaperOnly
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
//? if >=1.21.11 {
import net.kyori.adventure.text.`object`.ObjectContents
//?}
import org.bukkit.Material

/**
 * Renders an inline item-sprite icon with an item tooltip using the Adventure object / sprite hover
 * API introduced in Minecraft 1.21.11 (`ObjectContents`, [HoverEvent.showItem]). [render] is only ever
 * called behind `ServerVersion.isAtLeast_1_21_11` (see `MessageUtil.materialSpriteComponent`), but the
 * symbol still has to compile on every version for that single cross-version jar, so the pre-1.21.11
 * branch below is dead code kept only to satisfy the compiler there — it mirrors the plain-text
 * fallback `materialSpriteComponent` uses directly on those versions.
 */
@PaperOnly
internal object SpriteHoverRenderer {
    /**
     * Block items live in the `minecraft:blocks` atlas (`block/<name>`); pure items live in
     * `minecraft:items` (`item/<name>`).
     */
    fun render(mat: Material): Component {
        val ns = mat.key().namespace()
        val name = mat.key().value()
        val atlas: Key
        val spriteKey: Key
        if (mat.isBlock) {
            atlas = Key.key("minecraft", "blocks")
            spriteKey = Key.key(ns, "block/$name")
        } else {
            atlas = Key.key("minecraft", "items")
            spriteKey = Key.key(ns, "item/$name")
        }
        //? if >=1.21.11 {
        return Component.`object`(ObjectContents.sprite(atlas, spriteKey))
            .hoverEvent(HoverEvent.showItem(mat.key(), 1))
        //?} else
        /*val key = "$ns:$name"
        return Component.text(key).hoverEvent(HoverEvent.showItem(mat.key(), 1))*/
    }
}
