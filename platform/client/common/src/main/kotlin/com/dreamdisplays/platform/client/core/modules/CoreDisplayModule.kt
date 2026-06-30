package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.api.display.service.DisplayService
import com.dreamdisplays.api.display.service.DisplayServices
import com.dreamdisplays.api.runtime.DreamDisplaysModule
import com.dreamdisplays.api.runtime.ModuleContext
import com.dreamdisplays.api.runtime.register
import com.dreamdisplays.api.display.service.DisplayLookup
import com.dreamdisplays.api.display.service.DisplayMutationPort
import com.dreamdisplays.api.display.service.DisplaySystem
import com.dreamdisplays.core.display.service.impl.DefaultDisplayService
import com.dreamdisplays.api.display.service.DefaultDisplaySystem
import com.dreamdisplays.api.playback.PlaybackPort
import com.dreamdisplays.api.watchparty.WatchPartyPort
import com.dreamdisplays.platform.client.displays.MinecraftDisplayCommands

/** Installs the client-side display system and its public [DisplayService]. */
object CoreDisplayModule : DreamDisplaysModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplays:core_display"

    /** Installs the display system and its public [DisplayService]. */
    override fun install(context: ModuleContext) {
        val displaySystem = DefaultDisplaySystem(MinecraftDisplayCommands())
        val displayService = DefaultDisplayService(displaySystem, displaySystem)
        val services = context.services

        services.register<DisplaySystem>(displaySystem)
        services.register<DisplayLookup>(displaySystem)
        services.register<DisplayMutationPort>(displaySystem)
        services.register<PlaybackPort>(displaySystem)
        services.register<WatchPartyPort>(displaySystem)
        services.register(DisplayServices.DISPLAY, displayService)
    }
}
