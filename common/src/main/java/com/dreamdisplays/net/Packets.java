package com.dreamdisplays.net;

import com.dreamdisplays.Initializer;
import com.dreamdisplays.util.FacingUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.joml.Vector3i;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Packet definitions for mod network communication.
 */
@NullMarked
public final class Packets {

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> createType(String path) {
        return new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(Initializer.MOD_ID, path)
        );
    }

    public record Delete(UUID uuid) implements CustomPacketPayload {
        public static final Type<Delete> PACKET_ID = createType("delete");
        public static final StreamCodec<FriendlyByteBuf, Delete> PACKET_CODEC =
                StreamCodec.of(
                        (buf, packet) -> buf.writeUUID(packet.uuid),
                        buf -> new Delete(buf.readUUID())
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PACKET_ID;
        }
    }

    public record DisplayEnabled(boolean enabled) implements CustomPacketPayload {
        public static final Type<DisplayEnabled> PACKET_ID = createType("display_enabled");
        public static final StreamCodec<FriendlyByteBuf, DisplayEnabled> PACKET_CODEC =
                StreamCodec.of(
                        (buf, packet) -> buf.writeBoolean(packet.enabled),
                        buf -> new DisplayEnabled(buf.readBoolean())
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PACKET_ID;
        }
    }

    public record Info(
            UUID uuid,
            UUID ownerUuid,
            Vector3i pos,
            int width,
            int height,
            String url,
            FacingUtil facingUtil,
            boolean isSync,
            String lang
    ) implements CustomPacketPayload {
        public static final Type<Info> PACKET_ID = createType("display_info");
        public static final StreamCodec<FriendlyByteBuf, Info> PACKET_CODEC =
                StreamCodec.of(
                        (buf, packet) -> {
                            buf.writeUUID(packet.uuid);
                            buf.writeUUID(packet.ownerUuid);
                            buf.writeVarInt(packet.pos.x());
                            buf.writeVarInt(packet.pos.y());
                            buf.writeVarInt(packet.pos.z());
                            buf.writeVarInt(packet.width);
                            buf.writeVarInt(packet.height);
                            buf.writeUtf(packet.url);
                            buf.writeByte((int) packet.facingUtil.toPacket());
                            buf.writeBoolean(packet.isSync);
                            buf.writeUtf(packet.lang);
                        },
                        buf ->
                                new Info(
                                        buf.readUUID(),
                                        buf.readUUID(),
                                        new Vector3i(
                                                buf.readVarInt(),
                                                buf.readVarInt(),
                                                buf.readVarInt()
                                        ),
                                        buf.readVarInt(),
                                        buf.readVarInt(),
                                        buf.readUtf(),
                                        FacingUtil.fromPacket(buf.readByte()),
                                        buf.readBoolean(),
                                        buf.readUtf()
                                )
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PACKET_ID;
        }
    }

    public record Premium(boolean premium) implements CustomPacketPayload {
        public static final Type<Premium> PACKET_ID = createType("premium");
        public static final StreamCodec<FriendlyByteBuf, Premium> PACKET_CODEC =
                StreamCodec.of(
                        (buf, packet) -> buf.writeBoolean(packet.premium),
                        buf -> new Premium(buf.readBoolean())
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PACKET_ID;
        }
    }

    public record Report(UUID uuid) implements CustomPacketPayload {
        public static final Type<Report> PACKET_ID = createType("report");
        public static final StreamCodec<FriendlyByteBuf, Report> PACKET_CODEC =
                StreamCodec.of(
                        (buf, packet) -> buf.writeUUID(packet.uuid),
                        buf -> new Report(buf.readUUID())
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PACKET_ID;
        }
    }

    public record RequestSync(UUID uuid) implements CustomPacketPayload {
        public static final Type<RequestSync> PACKET_ID = createType(
                "req_sync"
        );
        public static final StreamCodec<
                FriendlyByteBuf,
                RequestSync
                > PACKET_CODEC = StreamCodec.of(
                (buf, packet) -> buf.writeUUID(packet.uuid),
                buf -> new RequestSync(buf.readUUID())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PACKET_ID;
        }
    }

    public record Sync(
            UUID uuid,
            boolean isSync,
            boolean currentState,
            long currentTime,
            long limitTime
    ) implements CustomPacketPayload {
        public static final Type<Sync> PACKET_ID = createType("sync");
        public static final StreamCodec<FriendlyByteBuf, Sync> PACKET_CODEC =
                StreamCodec.of(
                        (buf, packet) -> {
                            buf.writeUUID(packet.uuid);
                            buf.writeBoolean(packet.isSync);
                            buf.writeBoolean(packet.currentState);
                            buf.writeVarLong(packet.currentTime);
                            buf.writeVarLong(packet.limitTime);
                        },
                        buf ->
                                new Sync(
                                        buf.readUUID(),
                                        buf.readBoolean(),
                                        buf.readBoolean(),
                                        buf.readVarLong(),
                                        buf.readVarLong()
                                )
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PACKET_ID;
        }
    }

    public record Version(String version) implements CustomPacketPayload {
        public static final Type<Version> PACKET_ID = createType("version");
        public static final StreamCodec<FriendlyByteBuf, Version> PACKET_CODEC =
                StreamCodec.of(
                        (buf, packet) -> buf.writeUtf(packet.version),
                        buf -> new Version(buf.readUtf())
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PACKET_ID;
        }
    }

    public record ReportEnabled(boolean enabled) implements CustomPacketPayload {
        public static final Type<ReportEnabled> PACKET_ID = createType("report_enabled");
        public static final StreamCodec<FriendlyByteBuf, ReportEnabled> PACKET_CODEC =
                StreamCodec.of(
                        (buf, packet) -> buf.writeBoolean(packet.enabled),
                        buf -> new ReportEnabled(buf.readBoolean())
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PACKET_ID;
        }
    }

    public record SetVideo(UUID uuid, String url, String lang) implements CustomPacketPayload {
        public static final Type<SetVideo> PACKET_ID = createType("set_video");
        public static final StreamCodec<FriendlyByteBuf, SetVideo> PACKET_CODEC =
                StreamCodec.of(
                        (buf, packet) -> {
                            buf.writeUUID(packet.uuid);
                            buf.writeUtf(packet.url);
                            buf.writeUtf(packet.lang);
                        },
                        buf -> new SetVideo(buf.readUUID(), buf.readUtf(), buf.readUtf())
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PACKET_ID;
        }
    }

    public record ClearCache(List<UUID> displayUuids) implements CustomPacketPayload {
        public static final Type<ClearCache> PACKET_ID = createType("clear_cache");
        public static final StreamCodec<FriendlyByteBuf, ClearCache> PACKET_CODEC =
                StreamCodec.of(
                        (buf, packet) -> {
                            buf.writeVarInt(packet.displayUuids.size());
                            for (UUID uuid : packet.displayUuids) {
                                buf.writeUUID(uuid);
                            }
                        },
                        buf -> {
                            int size = buf.readVarInt();
                            List<UUID> uuids = new ArrayList<>(size);
                            for (int i = 0; i < size; i++) {
                                uuids.add(buf.readUUID());
                            }
                            return new ClearCache(uuids);
                        }
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PACKET_ID;
        }
    }
}
