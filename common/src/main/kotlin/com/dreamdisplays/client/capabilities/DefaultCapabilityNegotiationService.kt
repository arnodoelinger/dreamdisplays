package com.dreamdisplays.client.capabilities

import com.dreamdisplays.Initializer
import com.dreamdisplays.net.Packets
import com.dreamdisplays.protocol.ClientCapabilities
import com.dreamdisplays.protocol.ServerCapabilities
import com.dreamdisplays.utils.GeneralUtil
import org.slf4j.LoggerFactory

/**
 * Default [CapabilityNegotiationService]. Local capabilities are probed once via the
 * [ClientCapabilityDetector]; server capabilities are assembled incrementally from the legacy
 * handshake (the server answers a [Packets.Version] advertise with Premium / ReportEnabled
 * packets, which [com.dreamdisplays.managers.ClientPacketManager] merges in via [onServerCapabilities]).
 *
 * @since 1.8.0
 */
class DefaultCapabilityNegotiationService(
    private val detector: ClientCapabilityDetector,
) : CapabilityNegotiationService {

    /** Probed once on first access; capability detection is stable for the process lifetime. */
    override val localCapabilities: ClientCapabilities by lazy { detector.detect() }

    /** Updated incrementally as the handshake packets arrive; null until the first arrives. */
    @Volatile override var serverCapabilities: ServerCapabilities? = null
        private set

    /** True, once any server capability information has arrived. */
    override val isNegotiated: Boolean get() = serverCapabilities != null

    /**
     * Starts the handshake by sending the legacy [Packets.Version] packet; the server responds
     * with the per-feature packets that populate [serverCapabilities].
     */
    override fun advertise() {
        try {
            Initializer.sendPacket(Packets.Version(GeneralUtil.getModVersion()))
        } catch (e: Exception) {
            logger.error("Unable to advertise client version", e)
        }
    }

    /** Replaces the negotiated [serverCapabilities] snapshot wholesale. */
    override fun onServerCapabilities(capabilities: ServerCapabilities) {
        serverCapabilities = capabilities
    }

    /** True if the negotiated server allows [feature]; false before negotiation completes. */
    override fun isFeatureEnabled(feature: String): Boolean =
        serverCapabilities?.allowedFeatures?.contains(feature) == true

    private companion object {
        private val logger = LoggerFactory.getLogger("DreamDisplays/CapabilityNegotiation")
    }
}
