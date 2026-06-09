package com.dreamdisplays.client.capabilities

import com.dreamdisplays.protocol.ClientCapabilities
import com.dreamdisplays.protocol.ServerCapabilities

/**
 * Service responsible for negotiating capabilities between the client and server. Detects the client's abilities,
 * probes the server for capabilities, and negotiates the capabilities to use.
 *
 * @since 1.8.0
 */
interface CapabilityNegotiationService {
    /** The client's capabilities as detected by the service. */
    val localCapabilities: ClientCapabilities

    /** The server's capabilities as detected by the service. */
    val serverCapabilities: ServerCapabilities?

    /** True once the server has responded with capabilities. */
    val isNegotiated: Boolean

    /** Advertise the client's abilities to the server. Should be called once on connection. */
    fun advertise()

    /** Replaces the negotiated [serverCapabilities] snapshot wholesale. */
    fun onServerCapabilities(capabilities: ServerCapabilities)

    /** True if the negotiated server allows [feature]; false before negotiation completes. */
    fun isFeatureEnabled(feature: String): Boolean
}
