package com.dreamdisplays.platform.client.capabilities

import com.dreamdisplays.api.capability.ServerFeature
import com.dreamdisplays.core.protocol.ClientHello
import com.dreamdisplays.core.protocol.ServerHello

/**
 * Service responsible for negotiating capabilities between the client and server. Detects the client's abilities,
 * probes the server for capabilities, and negotiates the capabilities to use.
 */
interface CapabilityNegotiationService {
    /** The client's capabilities as detected by the service. */
    val localCapabilities: ClientHello

    /** The server's capabilities as detected by the service. */
    val serverCapabilities: ServerHello?

    /** True once the server has responded with capabilities. */
    val isNegotiated: Boolean

    /** Advertise the client's abilities to the server. Should be called once on connection. */
    fun advertise()

    /** Replaces the negotiated [serverCapabilities] snapshot wholesale. */
    fun onServerCapabilities(capabilities: ServerHello)

    /** True if the negotiated server allows [feature]; false before negotiation completes. */
    fun isFeatureEnabled(feature: ServerFeature): Boolean
}
