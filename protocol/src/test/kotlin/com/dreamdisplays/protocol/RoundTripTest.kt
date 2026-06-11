@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoundTripTest {
    private val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
    private val owner = UUID.randomUUID()

    private fun roundTrip(packet: DreamPacket) {
        assertEquals(packet, PacketRegistry.decode(PacketRegistry.encode(packet)))
    }

    @Test fun clientHello() = roundTrip(
        ClientHello(
            modVersion = "1.8.0",
            supportsPopout = true,
            maxTextureSize = 8192,
            supportedCodecs = listOf("h264", "vp9", "av1"),
            supportsAudio = false,
        )
    )

    @Test fun serverHello() = roundTrip(
        ServerHello(
            isPremium = true,
            isAdmin = true,
            isReportingEnabled = true,
            maxDisplays = 25,
            allowedFeatures = listOf("popout", "pip"),
        )
    )

    @Test fun displayInfoWithNegativeCoordinatesAndUnicode() = roundTrip(
        DisplayInfo(
            id = id,
            ownerId = owner,
            x = -3012, y = -64, z = 29999871,
            width = 16, height = 9,
            url = "https://youtu.be/abc?x=привет 世界",
            facing = 3,
            isSync = true,
            lang = "ru",
            isLocked = false,
        )
    )

    @Test fun defaultsRoundTrip() {
        roundTrip(DisplayInfo())
        roundTrip(ClientHello())
        roundTrip(ServerHello())
    }

    @Test fun remainingPackets() {
        roundTrip(DisplayDelete(id))
        roundTrip(DisplaySync(id, isSync = true, isPaused = true, currentTimeMs = -1, durationMs = Long.MAX_VALUE))
        roundTrip(RequestSync(id))
        roundTrip(SetVideo(id, "https://example.com/v.mp4", "en"))
        roundTrip(SetLocked(id, locked = false))
        roundTrip(ReportDisplay(id))
        roundTrip(SetDisplaysEnabled(enabled = false))
        roundTrip(ClearCache(listOf(id, owner)))
        roundTrip(ClearCache(emptyList()))
    }

    @Test fun unknownTypeIdIsIgnored() {
        val proto = ProtoBuf { }
        val bytes = proto.encodeToByteArray(Envelope.serializer(), Envelope(9999, byteArrayOf(1, 2, 3)))
        assertNull(PacketRegistry.decode(bytes))
    }

    @Test fun unknownFieldIsSkipped() {
        val proto = ProtoBuf { }
        val payload = proto.encodeToByteArray(DisplaySync.serializer(), DisplaySync(id, isPaused = true))
        val extraField = byteArrayOf((15 shl 3 or 0).toByte(), 42) // field 15, varint 42
        val frame = proto.encodeToByteArray(Envelope.serializer(), Envelope(5, payload + extraField))
        assertEquals(DisplaySync(id, isPaused = true), PacketRegistry.decode(frame))
    }

    @Test fun directionsMatchRegistry() {
        assertEquals(PacketDirection.CLIENT_TO_SERVER, PacketRegistry.directionOf(ClientHello()))
        assertEquals(PacketDirection.SERVER_TO_CLIENT, PacketRegistry.directionOf(ServerHello()))
        assertEquals(PacketDirection.BIDIRECTIONAL, PacketRegistry.directionOf(DisplaySync()))
    }
}
