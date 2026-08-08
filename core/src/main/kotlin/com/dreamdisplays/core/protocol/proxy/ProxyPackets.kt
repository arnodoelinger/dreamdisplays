@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.proxy

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Backend -> proxy: announces this backend on its first player join (its outbound plugin-message
 * queue only drains once someone is actually connected to ride the message on).
 */
@Serializable
data class BackendHello(
    @ProtoNumber(1) val pluginVersion: String = "",
    @ProtoNumber(2) val mcVersion: String = "",
    @ProtoNumber(3) val platform: String = "",
) : ProxyPacket

/**
 * Proxy -> backend: answers [BackendHello] with the backend's own configured server name (so the
 * backend never needs a `server_name` config key of its own) and the full current roster of
 * backend names in the network, refreshed whenever the roster changes.
 */
@Serializable
data class ProxyWelcome(
    @ProtoNumber(1) val yourServerName: String = "",
    @ProtoNumber(2) val allServerNames: List<String> = emptyList(),
    @ProtoNumber(3) val proxyNowMs: Long = 0L,
) : ProxyPacket

/**
 * Backend -> proxy: NTP-style clock probe, carrying only the backend's own send timestamp. Echoed
 * back by [ClockReply] so the backend can estimate proxy-vs-local clock offset and round-trip time.
 */
@Serializable
data class ClockProbe(
    @ProtoNumber(1) val backendSendMs: Long = 0L,
) : ProxyPacket

/**
 * Proxy -> backend: answers [ClockProbe], echoing [backendSendMs] back alongside the proxy's own
 * receive/send timestamps — the three points a backend needs to estimate one-way offset the usual
 * NTP way, without assuming symmetric latency.
 */
@Serializable
data class ClockReply(
    @ProtoNumber(1) val backendSendMs: Long = 0L,
    @ProtoNumber(2) val proxyRecvMs: Long = 0L,
    @ProtoNumber(3) val proxySendMs: Long = 0L,
) : ProxyPacket

/**
 * Backend -> proxy: forwards a `/display fullscreen start server <scope> ...` command. [scope] is
 * either `global` (every backend) or one specific backend name, already validated client-side by
 * [ProxyWelcome]-driven tab-complete but re-checked by the proxy since that roster can lag.
 * `mode` is the [com.dreamdisplays.api.playback.FullscreenMode] ordinal, wired as a raw `Int` the
 * same way every other protocol packet carries it (see `Packets.kt`) to keep `:core` decoupled from
 * needing `:api` for wire types.
 */
@Serializable
data class StartNetworkFullscreen(
    @ProtoNumber(1) val scope: String = "",
    @ProtoNumber(2) val ownerId: String = "",
    @ProtoNumber(3) val url: String = "",
    @ProtoNumber(4) val mode: Int = 0,
    @ProtoNumber(5) val forced: Boolean = false,
    @ProtoNumber(6) val volume: Float = -1f,
    @ProtoNumber(7) val loop: Boolean = false,
    @ProtoNumber(8) val quality: String = "",
    @ProtoNumber(9) val title: String = "",
    @ProtoNumber(10) val targetsRaw: String = "",
) : ProxyPacket

/**
 * Proxy -> backend: fan-out of a [StartNetworkFullscreen] to one target backend. [sessionId] is
 * shared verbatim across every backend in the broadcast (so `/display fullscreen stop <id>` and
 * `list` line up network-wide); [anchorProxyMs] is the proxy's wall-clock instant the broadcast
 * should appear synchronized from - each backend translates it to local time via
 * [com.dreamdisplays.platform.server.proxy.ProxyClock.toLocal] before handing it to
 * [com.dreamdisplays.api.playback.Timeline.start], never by rewriting the timeline's own local
 * `serverTimeMs` semantics.
 */
@Serializable
data class ApplyFullscreen(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val anchorProxyMs: Long = 0L,
    @ProtoNumber(11) val sharedDisplayId: String = "",
    @ProtoNumber(3) val ownerId: String = "",
    @ProtoNumber(4) val url: String = "",
    @ProtoNumber(5) val mode: Int = 0,
    @ProtoNumber(6) val forced: Boolean = false,
    @ProtoNumber(7) val volume: Float = -1f,
    @ProtoNumber(8) val loop: Boolean = false,
    @ProtoNumber(9) val quality: String = "",
    @ProtoNumber(10) val title: String = "",
    @ProtoNumber(12) val targetsRaw: String = "",
) : ProxyPacket

/**
 * Bidirectional: a backend requests a network session be stopped everywhere (from
 * `/display fullscreen stop <id>` on a session it doesn't own locally), or the proxy fans that stop
 * back out to every backend hosting it - same wire shape either way, direction tells them apart.
 */
@Serializable
data class StopNetworkFullscreen(
    @ProtoNumber(1) val sessionId: String = "",
) : ProxyPacket

/**
 * Backend -> proxy: reports the outcome of applying an [ApplyFullscreen]. [pending] is true when the
 * backend had zero online players to target - not an error, just "nothing to acknowledge yet"; the
 * proxy retries this backend's [ApplyFullscreen] on its next [BackendHello].
 */
@Serializable
data class NetworkFullscreenAck(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val reach: Int = 0,
    @ProtoNumber(3) val pending: Boolean = false,
) : ProxyPacket

/** Backend -> proxy: requests the current network-wide fullscreen roster, for `/display fullscreen list`. */
@Serializable
data class ListNetworkSessions(
    @ProtoNumber(1) val unused: Int = 0,
) : ProxyPacket

/** One network session's aggregate state, as reported in a [NetworkSessionList]. */
@Serializable
data class NetworkSessionInfo(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val scope: String = "",
    @ProtoNumber(3) val url: String = "",
    @ProtoNumber(4) val totalReach: Int = 0,
)

/** Proxy -> backend: answers [ListNetworkSessions] with every live network fullscreen session. */
@Serializable
data class NetworkSessionList(
    @ProtoNumber(1) val sessions: List<NetworkSessionInfo> = emptyList(),
) : ProxyPacket

/**
 * Backend -> proxy: a player finished the `dreamdisplays:v2` handshake on this backend — sent on
 * every join and every proxy-driven server switch, not just the once-per-restart [BackendHello].
 * The proxy answers with a [ReplayForPlayer] so network state (currently: which live network
 * fullscreen sessions apply here) survives a `/server` switch without the player noticing.
 */
@Serializable
data class PlayerReady(
    @ProtoNumber(1) val playerId: String = "",
) : ProxyPacket

/**
 * Proxy -> backend: replays live network fullscreen session ids that [playerId] should be shown here.
 * [minimizedSessionIds] is the subset they had collapsed to PiP before landing on this backend, so
 * the switch restores how they left it instead of re-opening the overlay full-screen.
 */
@Serializable
data class ReplayForPlayer(
    @ProtoNumber(1) val playerId: String = "",
    @ProtoNumber(2) val sessionIds: List<String> = emptyList(),
    @ProtoNumber(3) val minimizedSessionIds: List<String> = emptyList(),
) : ProxyPacket

/**
 * Proxy -> backend: tells the backend [playerId] is currently on ([from]) that a switch to [to] is
 * starting, sent while the player's connection to [from] is still open (`Velocity`
 * `ServerPreConnectEvent` / `Bungeecord` `ServerConnectEvent` both fire before the origin disconnects) -
 * lets that backend distinguish "player is transferring" from "player quit" for anything gated on
 * `PlayerQuitEvent` (currently: the watch-party host-disconnect grace timer).
 */
@Serializable
data class PlayerTransferring(
    @ProtoNumber(1) val playerId: String = "",
    @ProtoNumber(2) val from: String = "",
    @ProtoNumber(3) val to: String = "",
) : ProxyPacket

/**
 * Proxy -> backend: [playerId] actually left the whole network (proxy-level disconnect, not a
 * backend switch) - clears any [PlayerTransferring] suppression still armed for them on [server], so
 * a transfer attempt that was started but never completed doesn't permanently mask a real quit.
 */
@Serializable
data class PlayerLeftNetwork(
    @ProtoNumber(1) val playerId: String = "",
    @ProtoNumber(2) val server: String = "",
) : ProxyPacket

/**
 * Backend -> proxy: the host's backend requests a network-wide watch party. The proxy mints
 * [ApplyNetworkWatchParty.partyId] / [ApplyNetworkWatchParty.sharedDisplayId] and replies only to
 * this same backend — starting a network party is host-only, unlike network fullscreen's fan-out.
 */
@Serializable
data class StartNetworkWatchParty(
    @ProtoNumber(1) val hostId: String = "",
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val lang: String = "",
) : ProxyPacket

/**
 * Proxy -> backend: answers [StartNetworkWatchParty], sent only to the requesting (host) backend.
 * That backend creates a virtual display for [sharedDisplayId] and starts a `virtual`
 * [com.dreamdisplays.platform.server.playback.WatchPartyManager] session on it - the single
 * authoritative copy of the party's state; every other backend only ever relays what this one says.
 */
@Serializable
data class ApplyNetworkWatchParty(
    @ProtoNumber(1) val partyId: String = "",
    @ProtoNumber(2) val sharedDisplayId: String = "",
    @ProtoNumber(3) val hostId: String = "",
    @ProtoNumber(4) val url: String = "",
    @ProtoNumber(5) val lang: String = "",
) : ProxyPacket

/**
 * Proxy -> backend: registers [playerId] as a member of network party [partyId] on this backend.
 * Sent to the host's own backend for non-host local members, and to any other backend hosting a
 * member (including one who transferred there). The host's backend adds the member to its
 * authoritative session ([com.dreamdisplays.platform.server.playback.WatchPartyManager.addMember]);
 * every other backend just needs to know [sharedDisplayId] exists here and who to relay state to
 * ([com.dreamdisplays.platform.server.proxy.NetworkWatchPartyRelay]).
 */
@Serializable
data class JoinNetworkWatchParty(
    @ProtoNumber(1) val partyId: String = "",
    @ProtoNumber(2) val sharedDisplayId: String = "",
    @ProtoNumber(3) val playerId: String = "",
    @ProtoNumber(4) val hostId: String = "",
    @ProtoNumber(5) val url: String = "",
    @ProtoNumber(6) val lang: String = "",
) : ProxyPacket

/**
 * Bidirectional: the host's backend reports its `virtual` session's state (backend -> proxy, with
 * every timestamp already translated to the proxy's epoch via
 * [com.dreamdisplays.platform.server.proxy.ProxyClock.toProxy]), and the proxy relays that same
 * shape verbatim to every other backend hosting a member (proxy -> backend), each of which
 * translates back to its own local clock via
 * [com.dreamdisplays.platform.server.proxy.ProxyClock.toLocal] before forwarding to its members.
 * Field shape mirrors [com.dreamdisplays.core.protocol.WatchPartyState] (the client-facing packet)
 * one-for-one so translation at either end is a straight field copy.
 */
@Serializable
data class NetworkWatchPartyState(
    @ProtoNumber(1) val partyId: String = "",
    @ProtoNumber(2) val sharedDisplayId: String = "",
    @ProtoNumber(3) val state: Int = 0,
    @ProtoNumber(4) val hostId: String = "",
    @ProtoNumber(5) val hostName: String = "",
    @ProtoNumber(6) val url: String = "",
    @ProtoNumber(7) val lang: String = "",
    @ProtoNumber(8) val readyCount: Int = 0,
    @ProtoNumber(9) val nearbyCount: Int = 0,
    @ProtoNumber(10) val countdownStartEpochMs: Long = 0,
    @ProtoNumber(11) val positionMs: Long = 0,
    @ProtoNumber(12) val serverTimeMs: Long = 0,
    @ProtoNumber(13) val durationMs: Long = 0,
    @ProtoNumber(14) val paused: Boolean = true,
) : ProxyPacket

/**
 * Bidirectional: tears a network watch party down everywhere - a backend requests it (host closed
 * the party locally), or the proxy fans it back out to every backend with members, same as
 * [StopNetworkFullscreen].
 */
@Serializable
data class CloseNetworkWatchParty(
    @ProtoNumber(1) val partyId: String = "",
) : ProxyPacket

/**
 * Bidirectional: asks the rest of the network what video the display named by [token] (a full id or
 * the 8-character short id `/display list` shows) is playing, so `/display fullscreen start id <id>
 * server <scope>` works for a display that lives on a different backend — each backend's display
 * registry is local, so the backend running the command usually can't resolve it alone.
 *
 * Backend -> proxy with [originServer] empty; the proxy stamps the requesting backend's name in and
 * fans it out to every other backend. The proxy keeps no state of its own for this — the pending
 * request lives on [originServer]'s backend, which resumes on [DisplayTokenResolved].
 */
@Serializable
data class ResolveDisplayToken(
    @ProtoNumber(1) val requestId: String = "",
    @ProtoNumber(2) val token: String = "",
    @ProtoNumber(3) val originServer: String = "",
) : ProxyPacket

/**
 * Bidirectional: answers a [ResolveDisplayToken] from the backend that actually hosts the display,
 * carrying its currently loaded video [url]. Only backends that know the token reply at all, so a
 * request may draw no answer (unknown network-wide, or its backend has no players to carry the
 * plugin message); the requester expires it instead of waiting forever. The proxy routes the reply
 * back to [originServer] verbatim.
 */
@Serializable
data class DisplayTokenResolved(
    @ProtoNumber(1) val requestId: String = "",
    @ProtoNumber(2) val originServer: String = "",
    @ProtoNumber(3) val url: String = "",
) : ProxyPacket

/**
 * Backend -> proxy: a viewer collapsed a network fullscreen session to PiP, or restored it. Only the
 * backend they were on at the time sees the client's `FullscreenAck`, and it forgets the moment they
 * leave — parking the flag on the proxy is what lets the *next* backend replay it (see
 * [ReplayForPlayer.minimizedSessionIds]).
 */
@Serializable
data class PlayerFullscreenMinimized(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val playerId: String = "",
    @ProtoNumber(3) val minimized: Boolean = false,
) : ProxyPacket

/** One display a backend hosts, as advertised in a [BackendDisplayIndex]. */
@Serializable
data class DisplayIndexEntry(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val url: String = "",
)

/**
 * Backend -> proxy: everything this backend can play, so the proxy can answer a
 * [ResolveDisplayToken] from its own index instead of asking around. Plugin messages ride player
 * connections, so a backend with nobody on it can neither be asked nor answer — remembering what it
 * announced while it did have players is the only way `fullscreen start id <id>` works for a
 * display that lives on a currently empty server.
 */
@Serializable
data class BackendDisplayIndex(
    @ProtoNumber(1) val displays: List<DisplayIndexEntry> = emptyList(),
) : ProxyPacket
