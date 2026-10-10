package com.dreamdisplays.platform.client

import com.dreamdisplays.platform.client.ui.config.ClothConfigScreen
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import net.fabricmc.loader.api.FabricLoader

/** Gives `Mod Menu` the settings screen. Without `Cloth Config` there is no screen and no button. */
class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) return ConfigScreenFactory { null }
        return ConfigScreenFactory { parent -> ClothConfigScreen.create(parent) }
    }
}
