package com.dreamdisplays.core.runtime

/** API surface passed to modules during install and uninstall. */
interface ModuleContext {
    /** Registry of services available to modules. */
    val services: ServiceRegistry
}
