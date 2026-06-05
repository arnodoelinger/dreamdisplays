package com.dreamdisplays.server.utils.net

import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.net.Packets
import com.dreamdisplays.server.Main
import com.dreamdisplays.server.datatypes.FabricDisplayData
import com.dreamdisplays.server.datatypes.SyncData
import com.dreamdisplays.utils.FacingUtil
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.joml.Vector3i
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Utility object for sending packets to players.
 *
 * Provides methods to send various types of packets to players,
 * including display info, sync data, delete commands, and settings updates.
 */
@PaperOnly @NullMarked object PacketUtil {
    private val logger = LoggerFactory.getLogger("DreamDisplays/PacketUtil")
    private const val CHANNEL_DISPLAY_INFO = "dreamdisplays:display_info"
    private const val CHANNEL_SYNC = "dreamdisplays:sync"
    private const val CHANNEL_DELETE = "dreamdisplays:delete"
    private const val CHANNEL_PREMIUM = "dreamdisplays:premium"
    private const val CHANNEL_IS_ADMIN = "dreamdisplays:is_admin"
    private const val CHANNEL_DISPLAY_ENABLED = "dreamdisplays:display_enabled"
    private const val CHANNEL_REPORT_ENABLED = "dreamdisplays:report_enabled"
    private const val CHANNEL_CLEAR_CACHE = "dreamdisplays:clear_cache"

    private val plugin: Main by lazy { Main.getInstance() }

    /** Encodes and broadcasts a `display_info` packet describing a single display to [players]. */
    fun sendDisplayInfo(
        players: List<Player?>,
        id: UUID,
        ownerId: UUID,
        position: Vector,
        width: Int,
        height: Int,
        url: String,
        lang: String,
        facing: BlockFace,
        isSync: Boolean,
        isLocked: Boolean = true,
    ) {
        runCatching {
            val packet = buildPacket { output ->
                output.writeUUID(id)
                output.writeUUID(ownerId)
                output.writeVarInt(position.blockX)
                output.writeVarInt(position.blockY)
                output.writeVarInt(position.blockZ)
                output.writeVarInt(width)
                output.writeVarInt(height)
                output.writeString(url)
                output.writeByte(facing.toPacketByte().toInt())
                output.writeBoolean(isSync)
                output.writeString(lang)
                output.writeBoolean(isLocked)
            }

            sendPacket(players, CHANNEL_DISPLAY_INFO, packet)
        }.onFailure { e ->
            logger.warn("Failed to send display info packet", e)
        }
    }

    /** Encodes and broadcasts a `sync` packet carrying the current playback state. */
    fun sendSync(players: List<Player?>, syncData: SyncData) {
        val id = syncData.id ?: return

        runCatching {
            val packet = buildPacket { output ->
                output.writeUUID(id)
                output.writeBoolean(syncData.isSync)
                output.writeBoolean(syncData.currentState)
                output.writeVarLong(syncData.currentTime)
                output.writeVarLong(syncData.limitTime)
            }

            sendPacket(players, CHANNEL_SYNC, packet)
        }.onFailure { e ->
            logger.warn("Failed to send sync packet", e)
        }
    }

    /** Tells [players] to remove the display with [id] from their local registry. */
    fun sendDelete(players: List<Player?>, id: UUID) {
        runCatching {
            val packet = buildPacket { output ->
                output.writeUUID(id)
            }

            sendPacket(players, CHANNEL_DELETE, packet)
        }.onFailure { e ->
            logger.warn("Failed to send delete packet", e)
        }
    }

    /** Notifies [player] whether they currently have premium permissions. */
    fun sendPremium(player: Player, isPremium: Boolean) {
        sendBooleanPacket(player, CHANNEL_PREMIUM, isPremium)
    }

    /** Notifies [player] whether they are recognized as an admin (for delete privileges). */
    fun sendIsAdmin(player: Player, isAdmin: Boolean) {
        sendBooleanPacket(player, CHANNEL_IS_ADMIN, isAdmin)
    }

    /** Pushes the global displays-enabled flag for [player] to the client. */
    fun sendDisplayEnabled(player: Player, isEnabled: Boolean) {
        sendBooleanPacket(player, CHANNEL_DISPLAY_ENABLED, isEnabled)
    }

    /** Tells the client whether the report feature is enabled (i.e., a webhook is configured). */
    fun sendReportEnabled(player: Player, isEnabled: Boolean) {
        sendBooleanPacket(player, CHANNEL_REPORT_ENABLED, isEnabled)
    }

    /** Tells [players] to evict the listed display UUIDs from any local caches. */
    fun sendClearCache(players: List<Player?>, displayUuids: List<UUID>) {
        if (displayUuids.isEmpty()) return

        runCatching {
            val packet = buildPacket { output ->
                output.writeVarInt(displayUuids.size)
                displayUuids.forEach { uuid ->
                    output.writeUUID(uuid)
                }
            }

            sendPacket(players, CHANNEL_CLEAR_CACHE, packet)
        }.onFailure { e ->
            logger.warn("Failed to send clear cache packet", e)
        }
    }

    /** Sends a one-byte boolean payload on [channel] to [player], swallowing IO errors with a warning. */
    private fun sendBooleanPacket(player: Player, channel: String, value: Boolean) {
        runCatching {
            val packet = buildPacket { output ->
                output.writeBoolean(value)
            }
            player.sendPluginMessage(plugin, channel, packet)
        }.onFailure { e ->
            logger.warn("Failed to send $channel packet", e)
        }
    }

    /** Allocates a buffer, runs [builder] against a [DataOutputStream] and returns the resulting bytes. */
    private fun buildPacket(builder: (DataOutputStream) -> Unit): ByteArray {
        return ByteArrayOutputStream().use { byteStream ->
            DataOutputStream(byteStream).use { output ->
                builder(output)
            }
            byteStream.toByteArray()
        }
    }

    /** Sends an already-built [packet] on [channel] to every non-null player in [players]. */
    private fun sendPacket(players: List<Player?>, channel: String, packet: ByteArray) {
        players.filterNotNull().forEach { player ->
            player.sendPluginMessage(plugin, channel, packet)
        }
    }

    /** Writes a UUID as two big-endian longs. */
    private fun DataOutputStream.writeUUID(uuid: UUID) {
        writeLong(uuid.mostSignificantBits)
        writeLong(uuid.leastSignificantBits)
    }

    /** Writes [value] in Minecraft's VarInt encoding (1–5 bytes). */
    private fun DataOutputStream.writeVarInt(value: Int) {
        var current = value
        while ((current and -0x80) != 0) {
            writeByte((current and 0x7F) or 0x80)
            current = current ushr 7
        }
        writeByte(current and 0x7F)
    }

    /** Writes [value] in Minecraft's VarLong encoding (1–10 bytes). */
    private fun DataOutputStream.writeVarLong(value: Long) {
        var current = value
        while (true) {
            if ((current and 0x7FL.inv()) == 0L) {
                writeByte(current.toInt())
                return
            }
            writeByte((current.toInt() and 0x7F) or 0x80)
            current = current ushr 7
        }
    }

    /** Writes [text] as UTF-8 bytes prefixed by its byte length as a VarInt. */
    private fun DataOutputStream.writeString(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        writeVarInt(bytes.size)
        write(bytes)
    }

    /** Maps a cardinal [BlockFace] to its wire byte; non-cardinal faces fall back to north. */
    private fun BlockFace.toPacketByte(): Byte = when (this) {
        BlockFace.NORTH -> 0
        BlockFace.EAST -> 1
        BlockFace.SOUTH -> 2
        BlockFace.WEST -> 3
        else -> 0
    }

    /** Reads a UUID encoded as two big-endian longs by [writeUUID]. */
    fun DataInputStream.readUUID(): UUID {
        return UUID(readLong(), readLong())
    }

    /** Decodes a VarInt; throws [IOException] if the encoding exceeds 5 bytes. */
    fun DataInputStream.readVarInt(): Int {
        var result = 0
        var shift = 0
        var byte: Int

        do {
            if (shift >= 35) throw IOException("VarInt is too big.")

            byte = readUnsignedByte()
            result = result or ((byte and 0x7F) shl shift)
            shift += 7
        } while ((byte and 0x80) != 0)

        return result
    }

    /** Decodes a VarLong; throws if the encoding exceeds 10 bytes. */
    fun DataInputStream.readVarLong(): Long {
        var result = 0L
        var shift = 0
        var byte: Byte

        do {
            if (shift >= 70) throw RuntimeException("VarLong is too big.")

            byte = readByte()
            result = result or ((byte.toInt() and 0x7F).toLong() shl shift)
            shift += 7
        } while ((byte.toInt() and 0x80) != 0)

        return result
    }
}

/**
 * `Fabric`-specific implementation of [PacketUtil].
 */
@FabricOnly object FabricPacketUtil {
    /** Encodes and broadcasts a `display_info` packet describing a single display to [players]. */
    fun sendDisplayInfo(players: List<ServerPlayer>, display: FabricDisplayData) {
        val facing = directionToFacingUtil(display.facing)
        val packet = Packets.Info(
            uuid = display.id,
            ownerUuid = display.ownerId,
            pos = Vector3i(display.minX, display.minY, display.minZ),
            width = display.width,
            height = display.height,
            url = display.url,
            facingUtil = facing,
            isSync = display.isSync,
            lang = display.lang,
            isLocked = display.isLocked,
        )
        players.forEach { player ->
            runCatching { ServerPlayNetworking.send(player, packet) }
        }
    }

    /** Encodes and broadcasts a `sync` packet carrying the current playback state. */
    fun sendSync(players: List<ServerPlayer>, syncData: SyncData) {
        val id = syncData.id ?: return
        val packet = Packets.Sync(
            uuid = id,
            isSync = syncData.isSync,
            currentState = syncData.currentState,
            currentTime = syncData.currentTime,
            limitTime = syncData.limitTime
        )
        players.forEach { player ->
            runCatching { ServerPlayNetworking.send(player, packet) }
        }
    }

    /** Tells [players] to remove the display with [id] from their local registry. */
    fun sendDelete(players: List<ServerPlayer>, id: UUID) {
        val packet = Packets.Delete(id)
        players.forEach { player ->
            runCatching { ServerPlayNetworking.send(player, packet) }
        }
    }

    /** Notifies [player] whether they currently have premium permissions. */
    fun sendPremium(player: ServerPlayer, isPremium: Boolean) {
        runCatching { ServerPlayNetworking.send(player, Packets.Premium(isPremium)) }
    }

    /** Notifies [player] whether they are recognized as an admin (for delete privileges). */
    fun sendIsAdmin(player: ServerPlayer, isAdmin: Boolean) {
        runCatching { ServerPlayNetworking.send(player, Packets.IsAdmin(isAdmin)) }
    }

    /** Pushes the global displays-enabled flag for [player] to the client. */
    fun sendDisplayEnabled(player: ServerPlayer, isEnabled: Boolean) {
        runCatching { ServerPlayNetworking.send(player, Packets.DisplayEnabled(isEnabled)) }
    }

    /** Tells the client whether the report feature is enabled (i.e., a webhook is configured). */
    fun sendReportEnabled(player: ServerPlayer, isEnabled: Boolean) {
        runCatching { ServerPlayNetworking.send(player, Packets.ReportEnabled(isEnabled)) }
    }

    /** Tells [players] to evict the listed display UUIDs from any local caches. */
    fun sendClearCache(players: List<ServerPlayer>, uuids: List<UUID>) {
        if (uuids.isEmpty()) return
        val packet = Packets.ClearCache(uuids)
        players.forEach { player ->
            runCatching { ServerPlayNetworking.send(player, packet) }
        }
    }

    /** Maps a cardinal [Direction] to its wire byte; non-cardinal faces fall back to the north. */
    private fun directionToFacingUtil(direction: Direction): FacingUtil {
        return when (direction) {
            Direction.NORTH -> FacingUtil.NORTH
            Direction.EAST -> FacingUtil.EAST
            Direction.SOUTH -> FacingUtil.SOUTH
            Direction.WEST -> FacingUtil.WEST
            else -> FacingUtil.NORTH
        }
    }
}
