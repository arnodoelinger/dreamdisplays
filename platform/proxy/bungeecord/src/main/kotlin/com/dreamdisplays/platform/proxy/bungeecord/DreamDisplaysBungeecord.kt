package com.dreamdisplays.platform.proxy.bungeecord

import com.dreamdisplays.core.protocol.proxy.packets.ApplyNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.packets.BackendDisplayIndex
import com.dreamdisplays.core.protocol.proxy.packets.BackendHello
import com.dreamdisplays.core.protocol.proxy.packets.ClockProbe
import com.dreamdisplays.core.protocol.proxy.packets.ClockReply
import com.dreamdisplays.core.protocol.proxy.packets.CloseNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.packets.DisplayTokenResolved
import com.dreamdisplays.core.protocol.proxy.packets.ListNetworkSessions
import com.dreamdisplays.core.protocol.proxy.packets.NetworkFullscreenAck
import com.dreamdisplays.core.protocol.proxy.packets.NetworkSessionList
import com.dreamdisplays.core.protocol.proxy.packets.NetworkWatchPartyState
import com.dreamdisplays.core.protocol.proxy.packets.PlayerFullscreenMinimized
import com.dreamdisplays.core.protocol.proxy.packets.PlayerLeftNetwork
import com.dreamdisplays.core.protocol.proxy.packets.PlayerReady
import com.dreamdisplays.core.protocol.proxy.packets.PlayerTransferring
import com.dreamdisplays.core.protocol.proxy.packets.ProxyPacket
import com.dreamdisplays.core.protocol.proxy.ProxyPacketRegistry
import com.dreamdisplays.core.protocol.proxy.packets.ProxyWelcome
import com.dreamdisplays.core.protocol.proxy.packets.ReplayForPlayer
import com.dreamdisplays.core.protocol.proxy.packets.ResolveDisplayToken
import com.dreamdisplays.core.protocol.proxy.packets.StartNetworkFullscreen
import com.dreamdisplays.core.protocol.proxy.packets.StartNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.packets.StopNetworkFullscreen
import com.dreamdisplays.platform.proxy.NetworkBackendRegistry
import com.dreamdisplays.platform.proxy.NetworkDisplayIndex
import com.dreamdisplays.platform.proxy.NetworkFullscreenManager
import com.dreamdisplays.platform.proxy.NetworkTokenResolutions
import com.dreamdisplays.platform.proxy.NetworkWatchPartyManager
import io.github.arnodoelinger.platformweaver.BungeeCordOnly
import net.md_5.bungee.api.connection.Server
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PluginMessageEvent
import net.md_5.bungee.api.event.ServerConnectEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.event.EventHandler
import java.util.concurrent.TimeUnit

/** `dreamdisplays:proxy` channel tag, shared verbatim with the Velocity sibling (see that plugin's doc). */
private const val PROXY_CHANNEL = "dreamdisplays:proxy"

/** How often the stale-session sweep ([NetworkFullscreenManager.pruneStale]) runs. */
private const val PRUNE_INTERVAL_MS = 5L * 60L * 1000L

/**
 * `BungeeCord` entry point for the thin-coordinator proxy plugin. Same responsibilities as the `Velocity` equivalent:
 * relay fullscreen / session packets between backends.
 */
@BungeeCordOnly
class DreamDisplaysBungeecord : Plugin(), Listener {
    override fun onEnable() {
        proxy.registerChannel(PROXY_CHANNEL)
        proxy.pluginManager.registerListener(this, this)
        refreshKnownServers()
        logger.info(
            "Dream Displays proxy bridge ready on BungeeCord — " +
                    "${proxy.servers.size} backend(s) configured: ${NetworkBackendRegistry.allServerNames().sorted()}"
        )
        proxy.scheduler.schedule(
            this,
            { NetworkFullscreenManager.pruneStale(System.currentTimeMillis()) },
            PRUNE_INTERVAL_MS, PRUNE_INTERVAL_MS, TimeUnit.MILLISECONDS,
        )
    }

    /** `BungeeCord`'s server roster is config-driven and static at runtime, unlike `Velocity`'s — resynced on enable and on every hello for consistency with the `Velocity` sibling. */
    private fun refreshKnownServers() {
        NetworkBackendRegistry.updateKnownServers(proxy.servers.keys)
    }

    @EventHandler
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.tag != PROXY_CHANNEL) return
        val sender = event.sender as? Server ?: return
        event.isCancelled = true

        val packet = runCatching { ProxyPacketRegistry.decode(event.data) }.getOrNull() ?: return
        val serverName = sender.info.name
        when (packet) {
            is BackendHello -> {
                refreshKnownServers()
                NetworkBackendRegistry.recordHello(serverName, packet, System.currentTimeMillis())
                logger.info(
                    "Backend '$serverName' announced itself (Dream Displays ${packet.pluginVersion}, " +
                            "MC ${packet.mcVersion}, ${packet.platform})"
                )
                val welcome = ProxyWelcome(
                    yourServerName = serverName,
                    allServerNames = NetworkBackendRegistry.allServerNames().toList(),
                    proxyNowMs = System.currentTimeMillis(),
                )
                sender.sendData(PROXY_CHANNEL, ProxyPacketRegistry.encode(welcome))
                resendLiveSessions(serverName)
            }

            is ClockProbe -> {
                val proxyRecvMs = System.currentTimeMillis()
                val reply = ClockReply(
                    backendSendMs = packet.backendSendMs,
                    proxyRecvMs = proxyRecvMs,
                    proxySendMs = System.currentTimeMillis(),
                )
                sender.sendData(PROXY_CHANNEL, ProxyPacketRegistry.encode(reply))
            }

            is StartNetworkFullscreen -> {
                val session = NetworkFullscreenManager.start(packet, System.currentTimeMillis())
                val targets =
                    NetworkFullscreenManager.targetServers(session.scope, NetworkBackendRegistry.allServerNames())
                if (targets.isEmpty()) {
                    logger.warning("Network fullscreen '${session.sessionId}' from '$serverName' matched no backends for scope '${session.scope}'")
                }
                NetworkFullscreenManager.markPending(session.sessionId, targets)
                val apply = NetworkFullscreenManager.toApplyPacket(session)
                targets.forEach { name -> sendTo(name, apply) }
            }

            is NetworkFullscreenAck -> NetworkFullscreenManager.onAck(
                packet.sessionId,
                serverName,
                packet.reach,
                packet.pending
            )

            is StopNetworkFullscreen -> {
                val session = NetworkFullscreenManager.stop(packet.sessionId)
                val targets = session?.let {
                    NetworkFullscreenManager.targetServers(
                        it.scope,
                        NetworkBackendRegistry.allServerNames()
                    )
                }
                    ?: NetworkBackendRegistry.allServerNames()
                targets.forEach { name -> sendTo(name, packet) }
            }

            is ListNetworkSessions ->
                sender.sendData(
                    PROXY_CHANNEL,
                    ProxyPacketRegistry.encode(NetworkSessionList(NetworkFullscreenManager.list()))
                )

            is PlayerReady -> {
                retryPendingSessions(serverName)
                val applicable =
                    NetworkFullscreenManager.sessionIdsApplicableTo(serverName, NetworkBackendRegistry.allServerNames())
                val minimized = NetworkFullscreenManager.minimizedSessionIdsFor(packet.playerId, applicable)
                sendTo(serverName, ReplayForPlayer(packet.playerId, applicable, minimized))
            }

            is StartNetworkWatchParty -> {
                val party = NetworkWatchPartyManager.start(packet, hostServer = serverName)
                sendTo(
                    serverName,
                    ApplyNetworkWatchParty(
                        party.partyId,
                        party.sharedDisplayId.toString(),
                        party.hostId,
                        party.url,
                        party.lang
                    ),
                )
            }

            is NetworkWatchPartyState ->
                NetworkWatchPartyManager.relayTargets(packet.partyId).forEach { name -> sendTo(name, packet) }

            is CloseNetworkWatchParty -> {
                val targets = NetworkWatchPartyManager.relayTargets(packet.partyId)
                NetworkWatchPartyManager.stop(packet.partyId)
                targets.forEach { name -> sendTo(name, packet) }
            }

            is ResolveDisplayToken -> {
                val known = NetworkDisplayIndex.resolve(packet.token)
                if (known != null) {
                    sendTo(serverName, DisplayTokenResolved(packet.requestId, serverName, known))
                } else {
                    val stamped = packet.copy(originServer = serverName)
                    NetworkTokenResolutions.start(packet.requestId, serverName)
                    (NetworkBackendRegistry.allServerNames() - serverName).forEach { name -> sendTo(name, stamped) }
                    proxy.scheduler.schedule(
                        this,
                        { settleResolution(packet.requestId) },
                        NetworkTokenResolutions.FANOUT_WINDOW_MS,
                        TimeUnit.MILLISECONDS,
                    )
                }
            }

            is DisplayTokenResolved -> NetworkTokenResolutions.addReply(packet.requestId, packet.url)

            is BackendDisplayIndex -> NetworkDisplayIndex.update(serverName, packet)

            is PlayerFullscreenMinimized ->
                NetworkFullscreenManager.setMinimized(packet.sessionId, packet.playerId, packet.minimized)

            else -> logger.fine("Unhandled proxy packet from '$serverName': $packet")
        }
    }

    /**
     * Fires while the player's connection to their current backend is still open, before the switch
     * actually happens — tells that backend "this quit is a transfer" so it doesn't start any
     * host-disconnect grace timer for them. A same-server reconnect is not a transfer, so it's excluded.
     */
    @EventHandler
    fun onServerConnect(event: ServerConnectEvent) {
        val player = event.player
        val fromServer = player.server?.info?.name ?: return
        val toServer = event.target.name
        if (fromServer == toServer) return
        sendTo(fromServer, PlayerTransferring(player.uniqueId.toString(), fromServer, toServer))
    }

    /**
     * Fires on a whole-proxy disconnect (not a backend switch — that's [onServerConnect] followed by a normal connect),
     * so it can notify the last server the player truly left the network.
     */
    @EventHandler
    fun onPlayerDisconnect(event: PlayerDisconnectEvent) {
        val player = event.player
        val lastServer = player.server?.info?.name ?: return
        sendTo(lastServer, PlayerLeftNetwork(player.uniqueId.toString(), lastServer))
    }

    /** Sends [packet] to backend [serverName], if it's currently registered on this proxy. */
    private fun sendTo(serverName: String, packet: ProxyPacket) {
        proxy.getServerInfo(serverName)?.sendData(PROXY_CHANNEL, ProxyPacketRegistry.encode(packet))
    }

    /** Closes a [ResolveDisplayToken] fan-out window and forwards the answer, if [NetworkTokenResolutions.settle] found exactly one. */
    private fun settleResolution(requestId: String) {
        NetworkTokenResolutions.settle(requestId)?.let { (origin, url) ->
            sendTo(origin, DisplayTokenResolved(requestId, origin, url))
        }
    }

    /** Re-applies every network fullscreen session [serverName] is still owed, on a [PlayerReady]. */
    private fun retryPendingSessions(serverName: String) {
        NetworkFullscreenManager.pendingSessionsFor(serverName).forEach { session ->
            sendTo(serverName, NetworkFullscreenManager.toApplyPacket(session))
        }
    }

    /**
     * Re-applies every live session [serverName] scope-matches, on a fresh [BackendHello] — broader than the
     * pending-retry path since it covers sessions the backend never even acked.
     */
    private fun resendLiveSessions(serverName: String) {
        NetworkFullscreenManager.liveSessionsApplicableTo(serverName, NetworkBackendRegistry.allServerNames())
            .forEach { session ->
                NetworkFullscreenManager.markPending(session.sessionId, setOf(serverName))
                sendTo(serverName, NetworkFullscreenManager.toApplyPacket(session))
            }
    }
}
