package com.dreamdisplays.platform.client.utils

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

object MinecraftScreenUtil {
    //? if >=26 {
    // NeoForge 26.1.x keeps mc.screen as a public field; Fabric 26.2+ moved it to mc.gui.screen().
    // Detect once at startup to avoid per-frame exception overhead.
    private val getScreen: (Minecraft) -> Screen?
    private val setScreenFn: (Minecraft, Screen?) -> Unit

    init {
        val screenField = try { Minecraft::class.java.getField("screen") } catch (_: NoSuchFieldException) { null }
        if (screenField != null) {
            val setScreenMethod = Minecraft::class.java.getMethod("setScreen", Screen::class.java)
            getScreen = { mc -> screenField.get(mc) as? Screen }
            setScreenFn = { mc, s -> setScreenMethod.invoke(mc, s) }
        } else {
            val guiField = Minecraft::class.java.getField("gui")
            val screenMethod = guiField.type.getMethod("screen")
            val setScreenMethod = guiField.type.getMethod("setScreen", Screen::class.java)
            getScreen = { mc -> screenMethod.invoke(guiField.get(mc)) as? Screen }
            setScreenFn = { mc, s -> setScreenMethod.invoke(guiField.get(mc), s) }
        }
    }
    //?} else
    /**/

    fun currentScreen(mc: Minecraft): Screen? {
        //? if >=26 {
        return getScreen(mc)
        //?} else
        /*return mc.screen*/
    }

    fun setScreen(mc: Minecraft, screen: Screen?) {
        //? if >=26 {
        setScreenFn(mc, screen)
        //?} else
        /*mc.setScreen(screen)*/
    }
}
