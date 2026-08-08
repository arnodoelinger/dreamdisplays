@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.proxy

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProxyRoundTripTest {
    private fun roundTrip(packet: ProxyPacket) {
        assertEquals(packet, ProxyPacketRegistry.decode(ProxyPacketRegistry.encode(packet)))
    }

    @Test
    fun handshakePackets() {
        roundTrip(BackendHello(pluginVersion = "1.9.0", mcVersion = "1.21.11", platform = "paper"))
        roundTrip(ProxyWelcome(yourServerName = "lobby", allServerNames = listOf("lobby", "survival"), proxyNowMs = 1_700_000_000_000))
        roundTrip(ClockProbe(backendSendMs = 1_700_000_000_000))
        roundTrip(ClockReply(backendSendMs = 1_700_000_000_000, proxyRecvMs = 1_700_000_000_050, proxySendMs = 1_700_000_000_055))
    }

    @Test
    fun networkFullscreenPackets() {
        roundTrip(
            StartNetworkFullscreen(
                scope = "global", ownerId = "01234567-89ab-cdef-0123-456789abcdef",
                url = "https://youtu.be/abc?x=привет 世界", mode = 1, forced = true, volume = 0.75f,
                loop = true, quality = "1080", title = "Season finale", targetsRaw = "@a,%vip",
            )
        )
        roundTrip(
            ApplyFullscreen(
                sessionId = "a1b2c3d4", anchorProxyMs = 1_700_000_001_500,
                sharedDisplayId = "11111111-2222-3333-4444-555555555555",
                ownerId = "01234567-89ab-cdef-0123-456789abcdef", url = "https://youtu.be/abc",
                mode = 0, forced = false, volume = -1f, loop = false, quality = "", title = "",
                targetsRaw = "",
            )
        )
        roundTrip(StopNetworkFullscreen(sessionId = "a1b2c3d4"))
        roundTrip(NetworkFullscreenAck(sessionId = "a1b2c3d4", reach = 12, pending = false))
        roundTrip(ListNetworkSessions())
        roundTrip(
            NetworkSessionList(
                listOf(
                    NetworkSessionInfo(sessionId = "a1b2c3d4", scope = "global", url = "https://youtu.be/abc", totalReach = 12),
                    NetworkSessionInfo(),
                )
            )
        )
        roundTrip(PlayerReady(playerId = "01234567-89ab-cdef-0123-456789abcdef"))
        roundTrip(ReplayForPlayer(playerId = "01234567-89ab-cdef-0123-456789abcdef", sessionIds = listOf("a1b2c3d4", "e5f6a7b8"), minimizedSessionIds = listOf("e5f6a7b8")))
        roundTrip(PlayerFullscreenMinimized(sessionId = "a1b2c3d4", playerId = "01234567-89ab-cdef-0123-456789abcdef", minimized = true))
    }

    @Test
    fun transferAndIndexPackets() {
        roundTrip(PlayerTransferring(playerId = "01234567-89ab-cdef-0123-456789abcdef", from = "lobby", to = "survival"))
        roundTrip(PlayerLeftNetwork(playerId = "01234567-89ab-cdef-0123-456789abcdef", server = "lobby"))
        roundTrip(
            BackendDisplayIndex(
                listOf(
                    DisplayIndexEntry(id = "11111111-2222-3333-4444-555555555555", url = "https://youtu.be/abc"),
                    DisplayIndexEntry(),
                )
            )
        )
        roundTrip(ResolveDisplayToken(requestId = "req12345", token = "1234abcd", originServer = "survival"))
        roundTrip(DisplayTokenResolved(requestId = "req12345", originServer = "survival", url = "https://youtu.be/abc"))
    }

    @Test
    fun networkWatchPartyPackets() {
        roundTrip(StartNetworkWatchParty(hostId = "01234567-89ab-cdef-0123-456789abcdef", url = "https://youtu.be/abc", lang = "en"))
        roundTrip(
            ApplyNetworkWatchParty(
                partyId = "p1p2p3p4", sharedDisplayId = "11111111-2222-3333-4444-555555555555",
                hostId = "01234567-89ab-cdef-0123-456789abcdef", url = "https://youtu.be/abc", lang = "en",
            )
        )
        roundTrip(
            JoinNetworkWatchParty(
                partyId = "p1p2p3p4", sharedDisplayId = "11111111-2222-3333-4444-555555555555",
                playerId = "01234567-89ab-cdef-0123-456789abcdef",
                hostId = "01234567-89ab-cdef-0123-456789abcdef", url = "https://youtu.be/abc", lang = "en",
            )
        )
        roundTrip(
            NetworkWatchPartyState(
                partyId = "p1p2p3p4", sharedDisplayId = "11111111-2222-3333-4444-555555555555",
                state = 2, hostId = "01234567-89ab-cdef-0123-456789abcdef", hostName = "Steve",
                url = "https://youtu.be/abc", lang = "en", readyCount = 3, nearbyCount = 5,
                countdownStartEpochMs = 1_700_000_003_000, positionMs = 0,
                serverTimeMs = 1_700_000_000_000, durationMs = 600_000, paused = true,
            )
        )
        roundTrip(CloseNetworkWatchParty(partyId = "p1p2p3p4"))
    }

    @Test
    fun defaultsRoundTrip() {
        roundTrip(BackendHello())
        roundTrip(ProxyWelcome())
        roundTrip(ClockProbe())
        roundTrip(ClockReply())
        roundTrip(StartNetworkFullscreen())
        roundTrip(ApplyFullscreen())
        roundTrip(StopNetworkFullscreen())
        roundTrip(NetworkFullscreenAck())
        roundTrip(ListNetworkSessions())
        roundTrip(NetworkSessionList())
        roundTrip(PlayerReady())
        roundTrip(ReplayForPlayer())
        roundTrip(PlayerTransferring())
        roundTrip(PlayerLeftNetwork())
        roundTrip(StartNetworkWatchParty())
        roundTrip(ApplyNetworkWatchParty())
        roundTrip(JoinNetworkWatchParty())
        roundTrip(NetworkWatchPartyState())
        roundTrip(CloseNetworkWatchParty())
        roundTrip(ResolveDisplayToken())
        roundTrip(DisplayTokenResolved())
        roundTrip(PlayerFullscreenMinimized())
        roundTrip(BackendDisplayIndex())
    }

    @Test
    fun unknownTypeIdIsIgnored() {
        val proto = ProtoBuf { }
        val bytes = proto.encodeToByteArray(ProxyEnvelope.serializer(), ProxyEnvelope(9999, byteArrayOf(1, 2, 3)))
        assertNull(ProxyPacketRegistry.decode(bytes))
    }

    @Test
    fun directionsMatchRegistry() {
        assertEquals(ProxyPacketDirection.BACKEND_TO_PROXY, ProxyPacketRegistry.directionOf(BackendHello()))
        assertEquals(ProxyPacketDirection.PROXY_TO_BACKEND, ProxyPacketRegistry.directionOf(ProxyWelcome()))
        assertEquals(ProxyPacketDirection.BIDIRECTIONAL, ProxyPacketRegistry.directionOf(StopNetworkFullscreen()))
    }

    @Test
    fun decodeRejectsWrongDirection() {
        val bytes = ProxyPacketRegistry.encode(ProxyWelcome(yourServerName = "lobby"))
        assertEquals(
            ProxyWelcome(yourServerName = "lobby"),
            ProxyPacketRegistry.decode(bytes, ProxyPacketDirection.PROXY_TO_BACKEND),
        )
        runCatching { ProxyPacketRegistry.decode(bytes, ProxyPacketDirection.BACKEND_TO_PROXY) }
            .onSuccess { throw AssertionError("Expected decode to reject a PROXY_TO_BACKEND packet as BACKEND_TO_PROXY.") }
    }
}
