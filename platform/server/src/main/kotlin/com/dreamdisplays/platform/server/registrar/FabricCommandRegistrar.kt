package com.dreamdisplays.platform.server.registrar

import io.github.arnodoelinger.platformweaver.FabricOnly

/** `Fabric` event-registration adapter for [VanillaCommandTree]. */
@FabricOnly
object FabricCommandRegistrar {
    /** Registers the `/display` command tree with `Fabric`'s command dispatcher. */
    fun register() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.root.addChild(VanillaCommandTree.build())
        }
    }
}
