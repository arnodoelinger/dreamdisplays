package com.dreamdisplays.platform.server.proxy

import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Follower-side bookkeeping for a network watch party on a backend that isn't hosting its
 * authoritative session.
 */
object NetworkWatchPartyRelay {
    private class Party(val display: DisplayData) {
        val members: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    }

    private val parties = ConcurrentHashMap<String, Party>()

    /** Registers [display] as [partyId]'s local follower display, if not already known. */
    fun registerDisplay(partyId: String, display: DisplayData) {
        parties.putIfAbsent(partyId, Party(display))
    }

    /** The follower display for [partyId] on this backend, if one has been created here. */
    fun display(partyId: String): DisplayData? = parties[partyId]?.display

    /** Adds [playerId] as a local member of [partyId] (only meaningful after [registerDisplay]). */
    fun addMember(partyId: String, playerId: UUID) {
        parties[partyId]?.members?.add(playerId)
    }

    /** Every member of [partyId] present on this backend. */
    fun localMembers(partyId: String): Set<UUID> = parties[partyId]?.members ?: emptySet()

    /** Forgets [partyId] entirely (party closed); returns its display + members for teardown, or null if unknown here. */
    fun remove(partyId: String): Pair<DisplayData, Set<UUID>>? {
        val party = parties.remove(partyId) ?: return null
        return party.display to party.members
    }
}
