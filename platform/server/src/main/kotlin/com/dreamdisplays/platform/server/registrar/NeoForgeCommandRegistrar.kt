package com.dreamdisplays.platform.server.registrar

import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * `NeoForge` event-registration adapter for [VanillaCommandTree]. Registered on
 * `NeoForge.EVENT_BUS` from `NeoForgeServer` rather than `Fabric`'s `CommandRegistrationCallback`.
 */
@NeoForgeOnly
object NeoForgeCommandRegistrar {
    /** Registers the `/display` command tree against a `RegisterCommandsEvent`. */
    fun register(event: RegisterCommandsEvent) {
        event.dispatcher.root.addChild(VanillaCommandTree.build())
    }
}
