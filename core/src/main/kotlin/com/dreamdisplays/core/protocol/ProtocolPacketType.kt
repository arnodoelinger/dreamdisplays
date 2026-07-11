package com.dreamdisplays.core.protocol

import com.dreamdisplays.api.protocol.PacketDirection
import kotlin.reflect.KClass

/**
 * Append-only protocol-v2 packet type ids carried by [Envelope.type].
 *
 * These ids are part of the wire protocol. Never reuse or renumber existing entries; only append.
 */
enum class ProtocolPacketType(
    val id: Int,
    val packetClass: KClass<out DreamPacket>,
    val direction: PacketDirection,
) {
    CLIENT_HELLO(1, ClientHello::class, PacketDirection.CLIENT_TO_SERVER),
    SERVER_HELLO(2, ServerHello::class, PacketDirection.SERVER_TO_CLIENT),
    DISPLAY_INFO(3, DisplayInfo::class, PacketDirection.SERVER_TO_CLIENT),
    DISPLAY_DELETE(4, DisplayDelete::class, PacketDirection.BIDIRECTIONAL),
    DISPLAY_SYNC(5, DisplaySync::class, PacketDirection.BIDIRECTIONAL),
    REQUEST_SYNC(6, RequestSync::class, PacketDirection.CLIENT_TO_SERVER),
    SET_VIDEO(7, SetVideo::class, PacketDirection.CLIENT_TO_SERVER),
    SET_LOCKED(8, SetLocked::class, PacketDirection.CLIENT_TO_SERVER),
    REPORT_DISPLAY(9, ReportDisplay::class, PacketDirection.CLIENT_TO_SERVER),
    SET_DISPLAYS_ENABLED(10, SetDisplaysEnabled::class, PacketDirection.BIDIRECTIONAL),
    CLEAR_CACHE(11, ClearCache::class, PacketDirection.SERVER_TO_CLIENT),
    PLAYBACK_COMMAND(12, PlaybackCommand::class, PacketDirection.CLIENT_TO_SERVER),
    SET_MODE(13, SetMode::class, PacketDirection.CLIENT_TO_SERVER),
    WATCH_PARTY_START(14, WatchPartyStart::class, PacketDirection.CLIENT_TO_SERVER),
    WATCH_PARTY_CONTROL(15, WatchPartyControl::class, PacketDirection.CLIENT_TO_SERVER),
    WATCH_PARTY_STATE(16, WatchPartyState::class, PacketDirection.SERVER_TO_CLIENT),
    FULLSCREEN_STATE(17, FullscreenState::class, PacketDirection.SERVER_TO_CLIENT),
    FULLSCREEN_ACK(18, FullscreenAck::class, PacketDirection.CLIENT_TO_SERVER),
    RADIUS_PREVIEW(19, RadiusPreview::class, PacketDirection.SERVER_TO_CLIENT),
    PIP_PIN(20, PipPin::class, PacketDirection.CLIENT_TO_SERVER);

    companion object {
        private val byId = entries.associateBy { it.id }

        init {
            require(byId.size == entries.size) { "Duplicate protocol packet type ids." }
        }

        fun fromId(id: Int): ProtocolPacketType? = byId[id]
    }
}
