package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.api.runtime.DreamDisplaysModule
import com.dreamdisplays.api.runtime.ModuleContext
import com.dreamdisplays.api.runtime.register
import com.dreamdisplays.platform.client.input.CompositeInputHandler
import com.dreamdisplays.platform.client.input.DefaultKeyBindingRegistry
import com.dreamdisplays.platform.client.input.DisplayInteractionService
import com.dreamdisplays.platform.client.input.DisplayMenuInputHandler
import com.dreamdisplays.platform.client.input.InputHandler
import com.dreamdisplays.platform.client.input.KeyBindingRegistry
import com.dreamdisplays.platform.client.input.MinecraftDisplayInteractionService

/** Installs display interaction, key binding, and input dispatch services. */
object ClientInputModule : DreamDisplaysModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplays:client_input"

    /** Dependencies of this module. */
    override val dependencies: List<String> = listOf(CoreDisplayModule.id)

    /** Installs the display interaction service, key binding registry, and input dispatch service. */
    override fun install(context: ModuleContext) {
        val services = context.services
        services.register<DisplayInteractionService>(MinecraftDisplayInteractionService)
        services.register<KeyBindingRegistry>(
            DefaultKeyBindingRegistry().apply { register(DisplayMenuInputHandler.OPEN_MENU_BINDING) },
        )
        services.register<InputHandler>(
            CompositeInputHandler().apply { register(DisplayMenuInputHandler()) },
        )
    }
}
