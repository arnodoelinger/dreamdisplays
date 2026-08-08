package com.dreamdisplays.platform.proxy

import com.dreamdisplays.core.protocol.proxy.StartNetworkWatchParty
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Proxy-side authority for network watch parties: mints the shared party id and the shared virtual
 * display id every backend must agree on, and tracks which backend each member is currently on so a
 * host-driven state change can be relayed only to the backends that actually need it. Deliberately
 * platform-API-free, like [NetworkBackendRegistry] / [NetworkFullscreenManager].
 *
 * There is exactly one host per party, on exactly one backend — that backend's
 * [com.dreamdisplays.platform.server.playback.WatchPartyManager] session is the sole source of
 * truth; every other backend just relays whatever it says (see that class's doc for the full split).
 */
object NetworkWatchPartyManager {
    /** One live network watch party. */
    class Party(
        val partyId: String,
        val sharedDisplayId: UUID,
        val hostServer: String,
        val hostId: String,
        val url: String,
        val lang: String,
    ) {
        /** Member player id -> the backend they're currently on. */
        val members: MutableMap<UUID, String> = ConcurrentHashMap()
    }

    private val parties = ConcurrentHashMap<String, Party>()

    /** Starts a new party from a [request] forwarded by the host's backend, [hostServer]. */
    fun start(request: StartNetworkWatchParty, hostServer: String): Party {
        val party = Party(
            partyId = generatePartyId(),
            sharedDisplayId = UUID.randomUUID(),
            hostServer = hostServer,
            hostId = request.hostId,
            url = request.url,
            lang = request.lang,
        )
        parties[party.partyId] = party
        return party
    }

    /** The live party [partyId], if any. */
    fun party(partyId: String): Party? = parties[partyId]

    /** Registers [playerId] as a member of [partyId], currently on [serverName]. */
    fun addMember(partyId: String, playerId: UUID, serverName: String) {
        parties[partyId]?.members?.set(playerId, serverName)
    }

    /** Every backend (besides the host's own) currently hosting a member of [partyId]. */
    fun relayTargets(partyId: String): Set<String> {
        val party = parties[partyId] ?: return emptySet()
        return party.members.values.toSet() - party.hostServer
    }

    /** Stops and forgets [partyId]; returns the removed party, or null if it wasn't live. */
    fun stop(partyId: String): Party? = parties.remove(partyId)

    /** Same 8-character shape as a fullscreen network session id, for consistency across `/display` output. */
    private fun generatePartyId(): String {
        var id: String
        do id = UUID.randomUUID().toString().take(8) while (parties.containsKey(id))
        return id
    }
}
