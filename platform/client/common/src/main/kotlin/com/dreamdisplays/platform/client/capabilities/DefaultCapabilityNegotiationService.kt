package com.dreamdisplays.platform.client.capabilities

import com.dreamdisplays.api.capability.ServerFeature
import com.dreamdisplays.platform.client.net.ProtocolRouter
import com.dreamdisplays.core.protocol.ClientHello
import com.dreamdisplays.core.protocol.ServerHello
import com.dreamdisplays.core.protocol.hasFeature
import com.dreamdisplays.util.GeneralUtil
import org.slf4j.LoggerFactory

/**
 * Default [CapabilityNegotiationService]. Local capabilities are probed once via the
 * [ClientCapabilityDetector]; server capabilities arrive either as a single v2
 * [ServerHello] or merged incrementally from the legacy per-flag handshake packets
 * (via [com.dreamdisplays.platform.client.managers.ClientPacketManager]).
 */
class DefaultCapabilityNegotiationService(
    private val detector: ClientCapabilityDetector,
) : CapabilityNegotiationService {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/CapabilityNegotiation")

    /** Probed once on first access; capability detection is stable for the process lifetime. */
    override val localCapabilities: ClientHello by lazy {
        detector.detect().copy(modVersion = GeneralUtil.getModVersion())
    }

    /** Updated as handshake packets arrive; null until the first arrives. */
    @Volatile
    override var serverCapabilities: ServerHello? = null
        private set

    /** True, once any server capability information has arrived. */
    override val isNegotiated: Boolean get() = serverCapabilities != null

    /**
     * Starts the handshake: first the blind v2 [ClientHello] (ignored by pre-v2 servers), then the
     * legacy `version` packet so old servers run their v1 flow. Order matters — a v2 server must
     * mark the player as v2 before it processes the legacy packet.
     */
    override fun advertise() {
        runCatching { ProtocolRouter.sendV2(localCapabilities) }
            .onFailure { logger.debug("v2 hello not deliverable, staying on v1.", it) }
        try {
            ProtocolRouter.send(localCapabilities)
        } catch (e: Exception) {
            logger.error("Unable to advertise client version", e)
        }
    }

    /** Replaces the negotiated [serverCapabilities] snapshot wholesale. */
    override fun onServerCapabilities(capabilities: ServerHello) {
        serverCapabilities = capabilities
    }

    /** True if the negotiated server allows [feature]; false before negotiation completes. */
    override fun isFeatureEnabled(feature: ServerFeature): Boolean =
        serverCapabilities?.hasFeature(feature) == true
}
