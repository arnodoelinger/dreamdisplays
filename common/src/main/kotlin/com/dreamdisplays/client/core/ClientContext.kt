package com.dreamdisplays.client.core

import com.dreamdisplays.platform.api.Platform

/**
 * Context for a client application.
 *
 * @since 1.8.0
 */
interface ClientContext {
    /** Mutable state of the client, which can be updated by modules and observed by other modules. */
    val state: ClientMutableState

    /** Registry of services available to the client, which modules can use to provide and consume services. */
    val services: ServiceRegistry

    /** Platform-specific APIs and information, which modules can use to interact with the underlying platform. */
    val platform: Platform
}
