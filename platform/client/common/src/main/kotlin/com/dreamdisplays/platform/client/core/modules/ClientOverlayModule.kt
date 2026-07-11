package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.api.runtime.DreamDisplaysModule
import com.dreamdisplays.api.runtime.ModuleContext
import com.dreamdisplays.api.runtime.register
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.overlay.CrosshairPolicy
import com.dreamdisplays.platform.client.overlay.OverlayManager
import com.dreamdisplays.platform.client.popout.DefaultPopoutManager
import com.dreamdisplays.platform.client.popout.PopoutManager
import com.dreamdisplays.platform.client.ui.FullscreenOverlayManager
import com.dreamdisplays.platform.client.ui.PipOverlayManager

/** Installs overlay, crosshair, and popout services. */
object ClientOverlayModule : DreamDisplaysModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplays:client_overlay"

    /** Installs the overlay manager, crosshair policy, and popout manager. */
    override fun install(context: ModuleContext) {
        val services = context.services
        services.register<OverlayManager>(PipOverlayManager)
        services.register<CrosshairPolicy>(CrosshairPolicy {
            ClientStateManager.isOnScreen || FullscreenOverlayManager.isImmersiveActive
        })
        services.register<PopoutManager>(DefaultPopoutManager())
    }
}
