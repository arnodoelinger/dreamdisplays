package com.dreamdisplays.core.runtime

import com.dreamdisplays.api.runtime.ModuleContext
import com.dreamdisplays.api.runtime.ServiceRegistry

/** Default [ModuleContext] backed by a [ServiceRegistry]. */
class DefaultModuleContext(
    override val services: ServiceRegistry,
) : ModuleContext
