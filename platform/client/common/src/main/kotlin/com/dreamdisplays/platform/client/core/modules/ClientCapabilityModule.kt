package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.api.runtime.module.DreamDisplaysModule
import com.dreamdisplays.api.runtime.module.ModuleContext
import com.dreamdisplays.api.runtime.registry.service.register
import com.dreamdisplays.platform.client.capabilities.CapabilityNegotiationService
import com.dreamdisplays.platform.client.capabilities.ClientCapabilityDetector
import com.dreamdisplays.platform.client.capabilities.DefaultCapabilityNegotiationService
import com.dreamdisplays.platform.client.capabilities.MinecraftClientCapabilityDetector

/** Installs local capability detection and server capability negotiation services. */
object ClientCapabilityModule : DreamDisplaysModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplays:client_capability"

    /** Installs the capability detector and negotiation service. */
    override fun install(context: ModuleContext) {
        val detector = MinecraftClientCapabilityDetector
        val services = context.services
        services.register<ClientCapabilityDetector>(detector)
        services.register<CapabilityNegotiationService>(DefaultCapabilityNegotiationService(detector))
    }
}
