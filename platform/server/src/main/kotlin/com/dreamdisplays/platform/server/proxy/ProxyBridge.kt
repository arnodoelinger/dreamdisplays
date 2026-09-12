package com.dreamdisplays.platform.server.proxy

import com.dreamdisplays.api.playback.model.FullscreenMode
import com.dreamdisplays.api.playback.model.WatchPartySessionState
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.WatchPartyState
import com.dreamdisplays.core.protocol.proxy.packets.ApplyFullscreen
import com.dreamdisplays.core.protocol.proxy.packets.ApplyNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.packets.BackendDisplayIndex
import com.dreamdisplays.core.protocol.proxy.packets.DisplayIndexEntry
import com.dreamdisplays.core.protocol.proxy.packets.BackendHello
import com.dreamdisplays.core.protocol.proxy.packets.ClockProbe
import com.dreamdisplays.core.protocol.proxy.packets.ClockReply
import com.dreamdisplays.core.protocol.proxy.packets.CloseNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.packets.DisplayTokenResolved
import com.dreamdisplays.core.protocol.proxy.packets.JoinNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.packets.ListNetworkSessions
import com.dreamdisplays.core.protocol.proxy.packets.NetworkFullscreenAck
import com.dreamdisplays.core.protocol.proxy.packets.NetworkSessionList
import com.dreamdisplays.core.protocol.proxy.packets.NetworkWatchPartyState
import com.dreamdisplays.core.protocol.proxy.packets.PlayerFullscreenMinimized
import com.dreamdisplays.core.protocol.proxy.packets.PlayerLeftNetwork
import com.dreamdisplays.core.protocol.proxy.packets.PlayerReady
import com.dreamdisplays.core.protocol.proxy.packets.PlayerTransferring
import com.dreamdisplays.core.protocol.proxy.packets.ProxyPacket
import com.dreamdisplays.core.protocol.proxy.ProxyPacketDirection
import com.dreamdisplays.core.protocol.proxy.ProxyPacketRegistry
import com.dreamdisplays.core.protocol.proxy.packets.ProxyWelcome
import com.dreamdisplays.core.protocol.proxy.packets.ReplayForPlayer
import com.dreamdisplays.core.protocol.proxy.packets.ResolveDisplayToken
import com.dreamdisplays.core.protocol.proxy.packets.StartNetworkFullscreen
import com.dreamdisplays.core.protocol.proxy.packets.StartNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.packets.StopNetworkFullscreen
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The single `dreamdisplays:proxy` plugin-message channel, backend<->proxy only. */
const val PROXY_CHANNEL: String = "dreamdisplays:proxy"

/**
 * Backend side of the thin-coordinator proxy bridge.
 */
@PaperOnly
@NullMarked
object ProxyBridge : PluginMessageListener {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val plugin: PaperServer by lazy { PaperServer.getInstance() }
    private val helloSent = AtomicBoolean(false)
    private val lastClockProbeMs = AtomicLong(0L)

    /** Whether the `[proxy]` section is enabled in the active config; gates channel registration. */
    var enabled: Boolean = false
        private set

    /** `[proxy] clock_sync_interval`, in milliseconds; how often [tick] emits a [ClockProbe]. */
    private var clockSyncIntervalMs: Long = 5_000L

    /** How long a network display-id lookup waits for the backend that owns it to answer. */
    private const val RESOLVE_TIMEOUT_MS = 5_000L

    /**
     * A `/display fullscreen start id <id> server <scope>` held while the network is asked which
     * backend hosts `<id>` — see [ResolveDisplayToken]. Everything but the URL is already decided,
     * so the answer just fills in the last blank and the normal [startNetworkFullscreen] runs.
     */
    private class PendingNetworkStart(
        val playerId: UUID,
        val token: String,
        val scope: String,
        val mode: FullscreenMode?,
        val forced: Boolean,
        val volume: Float?,
        val loop: Boolean,
        val quality: String?,
        val targetsRaw: String?,
        val expiresAtMs: Long,
    )

    private val pendingStarts = ConcurrentHashMap<String, PendingNetworkStart>()

    /** Called from [com.dreamdisplays.platform.server.PaperServer.doEnable]. Resets per-session state. */
    fun init(enabled: Boolean, clockSyncIntervalSeconds: Int = 5) {
        this.enabled = enabled
        this.clockSyncIntervalMs = clockSyncIntervalSeconds.coerceAtLeast(1) * 1_000L
        helloSent.set(false)
        ProxyClock.reset()
        WatchPartyManager.onVirtualBroadcast = { displayId, snapshot -> relayWatchPartyState(displayId, snapshot) }
        WatchPartyManager.onVirtualClosed = { partyId -> sendViaAnyPlayer(CloseNetworkWatchParty(partyId)) }
        FullscreenBroadcastManager.onMinimizedChanged = { sessionId, playerId, minimized ->
            sendViaAnyPlayer(PlayerFullscreenMinimized(sessionId, playerId.toString(), minimized))
        }
    }

    /** Called from the v2 handshake when a player finishes negotiation; sends hello and display index. */
    fun onPlayerReady(player: Player) {
        if (!enabled) return
        if (helloSent.compareAndSet(false, true)) {
            send(
                player,
                BackendHello(
                    pluginVersion = plugin.pluginMeta.version,
                    mcVersion = Bukkit.getMinecraftVersion(),
                    platform = "paper",
                ),
            )
        }
        send(player, PlayerReady(playerId = player.uniqueId.toString()))
        sendDisplayIndex(player)
    }

    /**
     * Tells the proxy every display this backend can play, so `fullscreen start id <id>` resolves
     * network-wide even for a display whose backend is empty at the time — such a backend can be
     * neither asked nor heard, since plugin messages ride player connections.
     */
    private fun sendDisplayIndex(player: Player) {
        val entries = DisplayManager.getDisplays()
            .filter { it.url.isNotBlank() }
            .map { DisplayIndexEntry(it.id.toString(), it.url) }
        if (entries.isNotEmpty()) send(player, BackendDisplayIndex(entries))
    }

    /** Encodes and sends [packet] on [PROXY_CHANNEL] via [player]'s connection to the proxy. */
    private fun send(player: Player, packet: ProxyPacket) {
        val bytes = runCatching { ProxyPacketRegistry.encode(packet) }
            .onFailure { logger.warn("Failed to encode proxy packet", it) }
            .getOrNull() ?: return
        runCatching { player.sendPluginMessage(plugin, PROXY_CHANNEL, bytes) }
            .onFailure { logger.debug("Failed to send proxy packet (no proxy listening?)", it) }
    }

    /** Same as [send], but picks any online player to ride the message — for packets with no natural sender. */
    private fun sendViaAnyPlayer(packet: ProxyPacket) {
        val rider = Bukkit.getOnlinePlayers().firstOrNull() ?: return
        send(rider, packet)
    }

    /**
     * Forwards `/display fullscreen start server <scope> ...` to the proxy for network-wide fan-out.
     * Called from [com.dreamdisplays.platform.server.commands.subcommands.FullscreenCommand] instead
     * of [FullscreenBroadcastManager.start] when the command carried a `server` scope.
     */
    fun startNetworkFullscreen(
        player: Player,
        scope: String,
        url: String,
        mode: FullscreenMode?,
        forced: Boolean,
        volume: Float?,
        loop: Boolean,
        quality: String?,
        targetsRaw: String? = null,
    ) {
        send(
            player,
            StartNetworkFullscreen(
                targetsRaw = targetsRaw ?: "",
                scope = scope,
                ownerId = player.uniqueId.toString(),
                url = url,
                mode = (mode ?: FullscreenMode.STANDARD).ordinal,
                forced = forced,
                volume = volume ?: -1f,
                loop = loop,
                quality = quality ?: "",
            ),
        )
    }

    /** Starts a network fullscreen for an unknown id; asks the network to resolve it first. */
    fun startNetworkFullscreenByDisplayId(
        player: Player,
        scope: String,
        token: String,
        mode: FullscreenMode?,
        forced: Boolean,
        volume: Float?,
        loop: Boolean,
        quality: String?,
        targetsRaw: String? = null,
    ) {
        val now = System.currentTimeMillis()
        pendingStarts.values.removeIf { it.expiresAtMs < now }
        val requestId = UUID.randomUUID().toString().take(8)
        pendingStarts[requestId] = PendingNetworkStart(
            playerId = player.uniqueId,
            token = token,
            scope = scope,
            mode = mode,
            forced = forced,
            volume = volume,
            loop = loop,
            quality = quality,
            targetsRaw = targetsRaw,
            expiresAtMs = now + RESOLVE_TIMEOUT_MS,
        )
        send(player, ResolveDisplayToken(requestId = requestId, token = token))
    }

    /** Forwards `/display fullscreen stop <id>` to the proxy when [sessionId] isn't a live local session. */
    fun stopNetworkFullscreen(player: Player, sessionId: String) {
        send(player, StopNetworkFullscreen(sessionId))
    }

    /** Requests a new network-wide watch party, hosted by [player]. The proxy replies with [ApplyNetworkWatchParty]. */
    fun startNetworkWatchParty(player: Player, url: String, lang: String) {
        send(player, StartNetworkWatchParty(hostId = player.uniqueId.toString(), url = url, lang = lang))
    }

    /** Relays the host's own [WatchPartyState] snapshot to the proxy, translated to its epoch. */
    private fun relayWatchPartyState(displayId: UUID, snapshot: WatchPartyState) {
        sendViaAnyPlayer(
            NetworkWatchPartyState(
                partyId = snapshot.sessionId,
                sharedDisplayId = displayId.toString(),
                state = snapshot.state,
                hostId = snapshot.hostId.toString(),
                hostName = snapshot.hostName,
                url = snapshot.url,
                lang = snapshot.lang,
                readyCount = snapshot.readyCount,
                nearbyCount = snapshot.nearbyCount,
                countdownStartEpochMs = ProxyClock.toProxy(snapshot.countdownStartEpochMs),
                positionMs = snapshot.positionMs,
                serverTimeMs = ProxyClock.toProxy(snapshot.serverTimeMs),
                durationMs = snapshot.durationMs,
                paused = snapshot.paused,
            ),
        )
    }

    /**
     * Resolves a broadcast's `target` tokens against this backend's online players. Sender-relative
     * selectors (`@s`, `@p`, `@r`) are meaningless for a network broadcast — the commanding player is
     * on one backend only — so they are not offered here. Blank means everyone online.
     */
    private fun resolveNetworkTargets(targetsRaw: String): Set<UUID> {
        val online = Bukkit.getOnlinePlayers()
        if (targetsRaw.isBlank()) return online.map { it.uniqueId }.toSet()
        return targetsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.flatMapTo(mutableSetOf()) { token ->
            when {
                token.equals("@a", true) || token.equals("@e", true) -> online.map { it.uniqueId }
                token.startsWith("%") -> online.filter { it.hasPermission($$"group.${token.substring(1)}") }
                    .map { it.uniqueId }

                else -> listOfNotNull(Bukkit.getPlayerExact(token)?.uniqueId)
            }
        }
    }

    /** Requests a fresh [NetworkSessionList], refreshing [ProxyNetwork.networkSessions] for the next `/display fullscreen list`. */
    fun requestNetworkSessions(player: Player) {
        send(player, ListNetworkSessions())
    }

    /** Applies a proxy-driven [ApplyFullscreen], creating a fresh local virtual display for it. */
    private fun applyNetworkFullscreen(packet: ApplyFullscreen) {
        val ownerId = runCatching { UUID.fromString(packet.ownerId) }.getOrNull() ?: return
        val sharedId = runCatching { UUID.fromString(packet.sharedDisplayId) }.getOrNull()
        val resolved = FullscreenBroadcastManager.resolveOrCreateDisplay(packet.url, ownerId, sharedId)
        if (resolved == null) {
            logger.warn(
                "Could not create a virtual display for network fullscreen '{}' (no world loaded yet?)",
                packet.sessionId
            )
            sendViaAnyPlayer(NetworkFullscreenAck(packet.sessionId, reach = 0, pending = true))
            return
        }
        val (display, _) = resolved
        val onlineIds = resolveNetworkTargets(packet.targetsRaw)
        val mode = FullscreenMode.entries.getOrElse(packet.mode) { FullscreenMode.STANDARD }
        val sessionId = FullscreenBroadcastManager.start(
            sessionId = packet.sessionId,
            display = display,
            virtual = true,
            transientSession = true,
            ownerId = ownerId,
            mode = mode,
            forced = packet.forced,
            volume = packet.volume,
            loop = packet.loop,
            quality = packet.quality,
            title = packet.title,
            namedTargets = onlineIds,
            radius = null,
            timelineAnchorMs = ProxyClock.toLocal(packet.anchorProxyMs),
        )
        if (sessionId == null) {
            logger.warn(
                "Could not start network fullscreen '{}' locally (session id already taken here, or " +
                        "nobody online right now to target?)",
                packet.sessionId,
            )
        }
        val reach = if (sessionId != null) {
            FullscreenBroadcastManager.list().firstOrNull { it.sessionId == sessionId }?.reach ?: 0
        } else 0
        sendViaAnyPlayer(NetworkFullscreenAck(packet.sessionId, reach = reach, pending = sessionId == null))
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != PROXY_CHANNEL) return
        val packet = runCatching { ProxyPacketRegistry.decode(message, ProxyPacketDirection.PROXY_TO_BACKEND) }
            .onFailure { logger.warn("Failed to decode proxy packet", it) }
            .getOrNull() ?: return

        when (packet) {
            is ProxyWelcome -> {
                ProxyNetwork.update(packet.yourServerName, packet.allServerNames)
                logger.info(
                    "Proxy welcomed this backend as '{}' - network has {} server(s): {}",
                    packet.yourServerName, packet.allServerNames.size, packet.allServerNames,
                )
            }

            is ClockReply -> {
                val now = System.currentTimeMillis()
                ProxyClock.onClockReply(packet, now)
                logger.debug("Clock offset now ~{} ms (proxy - local)", ProxyClock.proxyNow() - now)
            }

            is ApplyFullscreen -> applyNetworkFullscreen(packet)

            is StopNetworkFullscreen -> FullscreenBroadcastManager.stop(packet.sessionId)

            is NetworkSessionList -> ProxyNetwork.updateSessions(packet.sessions)

            is ReplayForPlayer -> {
                val uuid = runCatching { UUID.fromString(packet.playerId) }.getOrNull() ?: return
                val minimized = packet.minimizedSessionIds.toSet()
                packet.sessionIds.forEach { sessionId ->
                    FullscreenBroadcastManager.addTarget(sessionId, uuid, sessionId in minimized)
                }
            }

            is PlayerTransferring -> {
                val uuid = runCatching { UUID.fromString(packet.playerId) }.getOrNull() ?: return
                TransferTracker.markTransferring(uuid)
            }

            is PlayerLeftNetwork -> {
                val uuid = runCatching { UUID.fromString(packet.playerId) }.getOrNull() ?: return
                TransferTracker.clear(uuid)
            }

            is ApplyNetworkWatchParty -> {
                val displayId = runCatching { UUID.fromString(packet.sharedDisplayId) }.getOrNull() ?: return
                val hostId = runCatching { UUID.fromString(packet.hostId) }.getOrNull() ?: return
                if (!WatchPartyManager.startVirtual(displayId, hostId, packet.url, packet.lang)) {
                    logger.warn(
                        "Could not start network watch party '{}' (no world loaded yet, or already live?)",
                        packet.partyId
                    )
                }
            }

            is JoinNetworkWatchParty -> {
                val displayId = runCatching { UUID.fromString(packet.sharedDisplayId) }.getOrNull() ?: return
                val playerId = runCatching { UUID.fromString(packet.playerId) }.getOrNull() ?: return
                val hostId = runCatching { UUID.fromString(packet.hostId) }.getOrNull() ?: return
                if (WatchPartyManager.isVirtual(displayId)) {
                    WatchPartyManager.addMember(displayId, playerId)
                } else {
                    val display = NetworkWatchPartyRelay.display(packet.partyId)
                        ?: WatchPartyManager.createFollowerDisplay(displayId, hostId)?.also {
                            NetworkWatchPartyRelay.registerDisplay(packet.partyId, it)
                        }
                    NetworkWatchPartyRelay.addMember(packet.partyId, playerId)
                    if (display != null && playerId in Bukkit.getOnlinePlayers().map { it.uniqueId }) {
                        WatchPartyManager.sendFollowerDisplayInfo(display, playerId)
                    }
                }
            }

            is NetworkWatchPartyState -> {
                val displayId = runCatching { UUID.fromString(packet.sharedDisplayId) }.getOrNull() ?: return
                val hostId = runCatching { UUID.fromString(packet.hostId) }.getOrNull() ?: return
                val wire = WatchPartyState(
                    id = displayId,
                    sessionId = packet.partyId,
                    state = packet.state,
                    hostId = hostId,
                    hostName = packet.hostName,
                    url = packet.url,
                    lang = packet.lang,
                    readyCount = packet.readyCount,
                    nearbyCount = packet.nearbyCount,
                    countdownStartEpochMs = ProxyClock.toLocal(packet.countdownStartEpochMs),
                    positionMs = packet.positionMs,
                    serverTimeMs = ProxyClock.toLocal(packet.serverTimeMs),
                    durationMs = packet.durationMs,
                    paused = packet.paused,
                )
                NetworkWatchPartyRelay.localMembers(packet.partyId).forEach { memberId ->
                    WatchPartyManager.sendToMember(memberId, wire)
                }
            }

            is CloseNetworkWatchParty -> {
                if (!WatchPartyManager.closeVirtual(packet.partyId)) {
                    val (display, members) = NetworkWatchPartyRelay.remove(packet.partyId) ?: return
                    val closing = WatchPartyState(
                        id = display.id, sessionId = "", state = WatchPartySessionState.ENDED.wire,
                    )
                    members.forEach { memberId ->
                        WatchPartyManager.sendToMember(memberId, closing)
                        WatchPartyManager.sendToMember(memberId, DisplayDelete(display.id))
                    }
                }
            }

            is ResolveDisplayToken -> {
                val url = FullscreenBroadcastManager.displayUrlByIdOrPrefix(packet.token) ?: return
                sendViaAnyPlayer(DisplayTokenResolved(packet.requestId, packet.originServer, url))
            }

            is DisplayTokenResolved -> {
                val pending = pendingStarts.remove(packet.requestId) ?: return
                if (pending.expiresAtMs < System.currentTimeMillis()) return
                val player = Bukkit.getPlayer(pending.playerId) ?: return
                logger.debug("Display '{}' resolved network-wide to {}.", pending.token, packet.url)
                startNetworkFullscreen(
                    player = player,
                    scope = pending.scope,
                    url = packet.url,
                    mode = pending.mode,
                    forced = pending.forced,
                    volume = pending.volume,
                    loop = pending.loop,
                    quality = pending.quality,
                    targetsRaw = pending.targetsRaw,
                )
            }

            else -> logger.debug("Ignoring non-backend-bound proxy packet {}.", packet::class.simpleName)
        }
    }

    /** Per-tick upkeep: emits a [ClockProbe] every `clock_sync_interval` seconds, once connected. */
    fun tick() {
        if (!enabled || !helloSent.get()) return
        val now = System.currentTimeMillis()
        if (now - lastClockProbeMs.get() < clockSyncIntervalMs) return
        val rider = Bukkit.getOnlinePlayers().firstOrNull() ?: return
        lastClockProbeMs.set(now)
        send(rider, ClockProbe(backendSendMs = now))
    }
}
