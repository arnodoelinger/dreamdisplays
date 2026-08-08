package com.dreamdisplays.platform.proxy.velocity

import com.dreamdisplays.core.protocol.proxy.ApplyNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.BackendDisplayIndex
import com.dreamdisplays.core.protocol.proxy.BackendHello
import com.dreamdisplays.core.protocol.proxy.ClockProbe
import com.dreamdisplays.core.protocol.proxy.ClockReply
import com.dreamdisplays.core.protocol.proxy.CloseNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.DisplayTokenResolved
import com.dreamdisplays.core.protocol.proxy.ListNetworkSessions
import com.dreamdisplays.core.protocol.proxy.NetworkFullscreenAck
import com.dreamdisplays.core.protocol.proxy.NetworkSessionList
import com.dreamdisplays.core.protocol.proxy.NetworkWatchPartyState
import com.dreamdisplays.core.protocol.proxy.PlayerFullscreenMinimized
import com.dreamdisplays.core.protocol.proxy.PlayerLeftNetwork
import com.dreamdisplays.core.protocol.proxy.PlayerReady
import com.dreamdisplays.core.protocol.proxy.PlayerTransferring
import com.dreamdisplays.core.protocol.proxy.ProxyPacket
import com.dreamdisplays.core.protocol.proxy.ProxyPacketRegistry
import com.dreamdisplays.core.protocol.proxy.ProxyWelcome
import com.dreamdisplays.core.protocol.proxy.ReplayForPlayer
import com.dreamdisplays.core.protocol.proxy.ResolveDisplayToken
import com.dreamdisplays.core.protocol.proxy.StartNetworkFullscreen
import com.dreamdisplays.core.protocol.proxy.StartNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.StopNetworkFullscreen
import com.dreamdisplays.platform.proxy.NetworkBackendRegistry
import com.dreamdisplays.platform.proxy.NetworkDisplayIndex
import com.dreamdisplays.platform.proxy.NetworkFullscreenManager
import com.dreamdisplays.platform.proxy.NetworkTokenResolutions
import com.dreamdisplays.platform.proxy.NetworkWatchPartyManager
import com.dreamdisplays.platform.proxy.VelocityOnly
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import org.slf4j.Logger
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * `dreamdisplays:proxy` channel id, shared verbatim by the `Bungee` sibling
 * ([com.dreamdisplays.platform.proxy.bungee.DreamDisplaysBungee]) — both sides must agree on the
 * literal string, plugin-messaging channel ids are not otherwise unified across the two proxy APIs.
 */
private const val PROXY_CHANNEL = "dreamdisplays:proxy"

/** How often the stale-session sweep ([NetworkFullscreenManager.pruneStale]) runs. */
private const val PRUNE_INTERVAL_MS = 5L * 60L * 1000L

/**
 * `Velocity` entry point for the thin-coordinator proxy plugin.
 *
 * Registers the `dreamdisplays:proxy` plugin-message channel, tracks the configured backend
 * roster, and answers each backend's [BackendHello] with a [ProxyWelcome] telling it its own
 * configured server name — the backend never needs to be told its own name in its own config.
 */
@VelocityOnly
@Plugin(
    id = "dreamdisplays-proxy",
    name = "DreamDisplays Proxy",
    version = "1.9.0-dev",
    authors = ["DreamDisplays"],
)
class DreamDisplaysVelocity @Inject constructor(
    private val proxyServer: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path,
) {
    private val channel = MinecraftChannelIdentifier.from(PROXY_CHANNEL)

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        proxyServer.channelRegistrar.register(channel)
        refreshKnownServers()
        logger.info(
            "DreamDisplays proxy bridge ready on Velocity - {} backend(s) configured: {}",
            proxyServer.allServers.size,
            NetworkBackendRegistry.allServerNames().sorted(),
        )
        proxyServer.scheduler.buildTask(this) { NetworkFullscreenManager.pruneStale(System.currentTimeMillis()) }
            .delay(PRUNE_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .repeat(PRUNE_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .schedule()
    }

    /**
     * Velocity's server roster can change at runtime (config reload, plugin-registered servers).
     * This `velocity-api` version has no dedicated server-(un)registration event to hook, so the
     * roster is instead re-synced on every [BackendHello] - cheap, and it's the same fallback the
     * Bungee sibling always uses (BungeeCord's roster is config-static, but re-syncing costs nothing).
     */
    private fun refreshKnownServers() {
        NetworkBackendRegistry.updateKnownServers(proxyServer.allServers.map { it.serverInfo.name })
    }

    @Subscribe
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != channel) return
        val source = event.source as? ServerConnection ?: return
        event.result = PluginMessageEvent.ForwardResult.handled()

        val packet = runCatching { ProxyPacketRegistry.decode(event.data) }.getOrNull() ?: return
        val serverName = source.serverInfo.name
        when (packet) {
            is BackendHello -> {
                refreshKnownServers()
                NetworkBackendRegistry.recordHello(serverName, packet, System.currentTimeMillis())
                logger.info(
                    "Backend '{}' announced itself (Dream Displays {}, MC {}, {})",
                    serverName, packet.pluginVersion, packet.mcVersion, packet.platform,
                )
                val welcome = ProxyWelcome(
                    yourServerName = serverName,
                    allServerNames = NetworkBackendRegistry.allServerNames().toList(),
                    proxyNowMs = System.currentTimeMillis(),
                )
                source.server.sendPluginMessage(channel, ProxyPacketRegistry.encode(welcome))
                resendLiveSessions(serverName)
            }

            is ClockProbe -> {
                val proxyRecvMs = System.currentTimeMillis()
                val reply = ClockReply(
                    backendSendMs = packet.backendSendMs,
                    proxyRecvMs = proxyRecvMs,
                    proxySendMs = System.currentTimeMillis(),
                )
                source.server.sendPluginMessage(channel, ProxyPacketRegistry.encode(reply))
            }

            is StartNetworkFullscreen -> {
                val session = NetworkFullscreenManager.start(packet, System.currentTimeMillis())
                val targets = NetworkFullscreenManager.targetServers(session.scope, NetworkBackendRegistry.allServerNames())
                if (targets.isEmpty()) {
                    logger.warn("Network fullscreen '{}' from '{}' matched no backends for scope '{}'", session.sessionId, serverName, session.scope)
                }
                NetworkFullscreenManager.markPending(session.sessionId, targets)
                val apply = NetworkFullscreenManager.toApplyPacket(session)
                targets.forEach { name -> sendTo(name, apply) }
            }

            is NetworkFullscreenAck -> NetworkFullscreenManager.onAck(packet.sessionId, serverName, packet.reach, packet.pending)

            is StopNetworkFullscreen -> {
                val session = NetworkFullscreenManager.stop(packet.sessionId)
                val targets = session?.let { NetworkFullscreenManager.targetServers(it.scope, NetworkBackendRegistry.allServerNames()) }
                    ?: NetworkBackendRegistry.allServerNames() // unknown locally - fan out anyway, a no-op stop() is harmless
                targets.forEach { name -> sendTo(name, packet) }
            }

            is ListNetworkSessions -> source.server.sendPluginMessage(channel, ProxyPacketRegistry.encode(NetworkSessionList(NetworkFullscreenManager.list())))

            is PlayerReady -> {
                retryPendingSessions(serverName)
                val applicable = NetworkFullscreenManager.sessionIdsApplicableTo(serverName, NetworkBackendRegistry.allServerNames())
                val minimized = NetworkFullscreenManager.minimizedSessionIdsFor(packet.playerId, applicable)
                sendTo(serverName, ReplayForPlayer(packet.playerId, applicable, minimized))
            }

            is StartNetworkWatchParty -> {
                val party = NetworkWatchPartyManager.start(packet, hostServer = serverName)
                sendTo(
                    serverName,
                    ApplyNetworkWatchParty(party.partyId, party.sharedDisplayId.toString(), party.hostId, party.url, party.lang),
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
                    proxyServer.scheduler.buildTask(this) { settleResolution(packet.requestId) }
                        .delay(NetworkTokenResolutions.FANOUT_WINDOW_MS, TimeUnit.MILLISECONDS)
                        .schedule()
                }
            }

            is DisplayTokenResolved -> NetworkTokenResolutions.addReply(packet.requestId, packet.url)

            is BackendDisplayIndex -> NetworkDisplayIndex.update(serverName, packet)

            is PlayerFullscreenMinimized ->
                NetworkFullscreenManager.setMinimized(packet.sessionId, packet.playerId, packet.minimized)

            else -> logger.debug("Unhandled proxy packet from '{}': {}", serverName, packet)
        }
    }

    /**
     * Fires while the player's connection to their current backend is still open, before the switch
     * actually happens — tells that backend "this quit is a transfer" so it doesn't start any
     * host-disconnect grace timer for them. A same-server reconnect (rare, but possible) is not a
     * transfer, so it's excluded.
     */
    @Subscribe
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        val fromServer = event.player.currentServer.orElse(null)?.serverInfo?.name ?: return
        val toServer = event.result.server.orElse(event.originalServer)
        if (fromServer == toServer.serverInfo.name) return
        sendTo(fromServer, PlayerTransferring(event.player.uniqueId.toString(), fromServer, toServer.serverInfo.name))
    }

    /**
     * Fires on a whole-proxy disconnect (not a backend switch — that's [onServerPreConnect] followed
     * by a normal connect, with no [DisconnectEvent] in between). Clears any [PlayerTransferring]
     * mark the last backend might still be holding, so an interrupted transfer attempt can't mask a
     * later real quit for the rest of its TTL.
     */
    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val lastServer = event.player.currentServer.orElse(null)?.serverInfo?.name ?: return
        sendTo(lastServer, PlayerLeftNetwork(event.player.uniqueId.toString(), lastServer))
    }

    /** Sends [packet] to backend [serverName], if it's currently registered on this proxy. */
    private fun sendTo(serverName: String, packet: ProxyPacket) {
        proxyServer.getServer(serverName).ifPresent { it.sendPluginMessage(channel, ProxyPacketRegistry.encode(packet)) }
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
     * Re-applies every live session [serverName] scope-matches, on a fresh [BackendHello] — broader
     * than [retryPendingSessions]: a `BackendHello` means that backend's own process just started, so
     * a session it already acked before a restart (crash, routine restart) needs reapplying too, since
     * `ApplyFullscreen` sessions are never persisted locally and the proxy would otherwise never resend
     * one it already believes succeeded.
     */
    private fun resendLiveSessions(serverName: String) {
        NetworkFullscreenManager.liveSessionsApplicableTo(serverName, NetworkBackendRegistry.allServerNames()).forEach { session ->
            NetworkFullscreenManager.markPending(session.sessionId, setOf(serverName))
            sendTo(serverName, NetworkFullscreenManager.toApplyPacket(session))
        }
    }
}
