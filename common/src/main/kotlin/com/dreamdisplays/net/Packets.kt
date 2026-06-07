package com.dreamdisplays.net

import com.dreamdisplays.Initializer
import com.dreamdisplays.utils.FacingUtil
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import org.joml.Vector3i
import java.util.*

private typealias PacketBuf = RegistryFriendlyByteBuf

/**
 * Packets used for communication between the client and the server.
 */
object Packets {
    /** Creates a [CustomPacketPayload.Type] with the given [path]. */
    private fun <T : CustomPacketPayload> createType(path: String): CustomPacketPayload.Type<T> =
        CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, path))

    data class Delete(val uuid: UUID) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<Delete> = createType("delete")
            val PACKET_CODEC: StreamCodec<PacketBuf, Delete> = StreamCodec.of(
                { buf, packet -> buf.writeUUID(packet.uuid) },
                { buf -> Delete(buf.readUUID()) }
            )
        }
    }

    data class DisplayEnabled(val enabled: Boolean) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<DisplayEnabled> = createType("display_enabled")
            val PACKET_CODEC: StreamCodec<PacketBuf, DisplayEnabled> = StreamCodec.of(
                { buf, packet -> buf.writeBoolean(packet.enabled) },
                { buf -> DisplayEnabled(buf.readBoolean()) }
            )
        }
    }

    data class Info(
        val uuid: UUID,
        val ownerUuid: UUID,
        val pos: Vector3i,
        val width: Int,
        val height: Int,
        val url: String,
        val facingUtil: FacingUtil,
        val isSync: Boolean,
        val lang: String,
        val isLocked: Boolean? = null,
    ) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<Info> = createType("display_info")
            val PACKET_CODEC: StreamCodec<PacketBuf, Info> = StreamCodec.of(
                { buf, packet ->
                    buf.writeUUID(packet.uuid)
                    buf.writeUUID(packet.ownerUuid)
                    buf.writeVarInt(packet.pos.x())
                    buf.writeVarInt(packet.pos.y())
                    buf.writeVarInt(packet.pos.z())
                    buf.writeVarInt(packet.width)
                    buf.writeVarInt(packet.height)
                    buf.writeUtf(packet.url)
                    buf.writeByte(packet.facingUtil.toPacket().toInt())
                    buf.writeBoolean(packet.isSync)
                    buf.writeUtf(packet.lang)
                    buf.writeBoolean(packet.isLocked ?: true)
                },
                { buf ->
                    Info(
                        buf.readUUID(),
                        buf.readUUID(),
                        Vector3i(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readUtf(),
                        FacingUtil.fromPacket(buf.readByte()),
                        buf.readBoolean(),
                        buf.readUtf(),
                        if (buf.readableBytes() > 0) buf.readBoolean() else null,
                    )
                }
            )
        }
    }

    data class SetLocked(val uuid: UUID, val locked: Boolean) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<SetLocked> = createType("set_locked")
            val PACKET_CODEC: StreamCodec<PacketBuf, SetLocked> = StreamCodec.of(
                { buf, packet -> buf.writeUUID(packet.uuid); buf.writeBoolean(packet.locked) },
                { buf -> SetLocked(buf.readUUID(), buf.readBoolean()) }
            )
        }
    }

    data class IsAdmin(val isAdmin: Boolean) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<IsAdmin> = createType("is_admin")
            val PACKET_CODEC: StreamCodec<PacketBuf, IsAdmin> = StreamCodec.of(
                { buf, packet -> buf.writeBoolean(packet.isAdmin) },
                { buf -> IsAdmin(buf.readBoolean()) }
            )
        }
    }

    data class Premium(val premium: Boolean) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<Premium> = createType("premium")
            val PACKET_CODEC: StreamCodec<PacketBuf, Premium> = StreamCodec.of(
                { buf, packet -> buf.writeBoolean(packet.premium) },
                { buf -> Premium(buf.readBoolean()) }
            )
        }
    }

    data class Report(val uuid: UUID) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<Report> = createType("report")
            val PACKET_CODEC: StreamCodec<PacketBuf, Report> = StreamCodec.of(
                { buf, packet -> buf.writeUUID(packet.uuid) },
                { buf -> Report(buf.readUUID()) }
            )
        }
    }

    data class RequestSync(val uuid: UUID) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<RequestSync> = createType("req_sync")
            val PACKET_CODEC: StreamCodec<PacketBuf, RequestSync> = StreamCodec.of(
                { buf, packet -> buf.writeUUID(packet.uuid) },
                { buf -> RequestSync(buf.readUUID()) }
            )
        }
    }

    data class Sync(
        val uuid: UUID,
        val isSync: Boolean,
        val currentState: Boolean,
        val currentTime: Long,
        val limitTime: Long
    ) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<Sync> = createType("sync")
            val PACKET_CODEC: StreamCodec<PacketBuf, Sync> = StreamCodec.of(
                { buf, packet ->
                    buf.writeUUID(packet.uuid)
                    buf.writeBoolean(packet.isSync)
                    buf.writeBoolean(packet.currentState)
                    buf.writeVarLong(packet.currentTime)
                    buf.writeVarLong(packet.limitTime)
                },
                { buf ->
                    Sync(
                        buf.readUUID(),
                        buf.readBoolean(),
                        buf.readBoolean(),
                        buf.readVarLong(),
                        buf.readVarLong()
                    )
                }
            )
        }
    }

    data class Version(val version: String) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<Version> = createType("version")
            val PACKET_CODEC: StreamCodec<PacketBuf, Version> = StreamCodec.of(
                { buf, packet -> buf.writeUtf(packet.version) },
                { buf -> Version(buf.readUtf()) }
            )
        }
    }

    data class ReportEnabled(val enabled: Boolean) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<ReportEnabled> = createType("report_enabled")
            val PACKET_CODEC: StreamCodec<PacketBuf, ReportEnabled> = StreamCodec.of(
                { buf, packet -> buf.writeBoolean(packet.enabled) },
                { buf -> ReportEnabled(buf.readBoolean()) }
            )
        }
    }

    data class SetVideo(val uuid: UUID, val url: String, val lang: String) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<SetVideo> = createType("set_video")
            val PACKET_CODEC: StreamCodec<PacketBuf, SetVideo> = StreamCodec.of(
                { buf, packet ->
                    buf.writeUUID(packet.uuid)
                    buf.writeUtf(packet.url)
                    buf.writeUtf(packet.lang)
                },
                { buf -> SetVideo(buf.readUUID(), buf.readUtf(), buf.readUtf()) }
            )
        }
    }

    data class ClearCache(val displayUuids: List<UUID>) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<ClearCache> = createType("clear_cache")
            val PACKET_CODEC: StreamCodec<PacketBuf, ClearCache> = StreamCodec.of(
                { buf, packet ->
                    buf.writeVarInt(packet.displayUuids.size)
                    packet.displayUuids.forEach { buf.writeUUID(it) }
                },
                { buf ->
                    val size = buf.readVarInt()
                    ClearCache(List(size) { buf.readUUID() })
                }
            )
        }
    }
}
