package com.dreamdisplays.utils.net

import com.dreamdisplays.Main
import com.dreamdisplays.datatypes.SyncData
import me.inotsleep.utils.logging.LoggingManager.warn
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.jspecify.annotations.NullMarked
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
 *
 * `Paper` implementation.
 */
@NullMarked
object PacketUtil {
    private const val CHANNEL_DISPLAY_INFO = "dreamdisplays:display_info"
    private const val CHANNEL_SYNC = "dreamdisplays:sync"
    private const val CHANNEL_DELETE = "dreamdisplays:delete"
    private const val CHANNEL_PREMIUM = "dreamdisplays:premium"
    private const val CHANNEL_DISPLAY_ENABLED = "dreamdisplays:display_enabled"
    private const val CHANNEL_REPORT_ENABLED = "dreamdisplays:report_enabled"
    private const val CHANNEL_CLEAR_CACHE = "dreamdisplays:clear_cache"

    private val plugin: Main by lazy { Main.getInstance() }

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
        }.onFailure { error ->
            warn("[PacketUtil] Failed to send display info packet", error)
        }
    }

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
        }.onFailure { error ->
            warn("[PacketUtil] Failed to send sync packet", error)
        }
    }

    fun sendDelete(players: List<Player?>, id: UUID) {
        runCatching {
            val packet = buildPacket { output ->
                output.writeUUID(id)
            }

            sendPacket(players, CHANNEL_DELETE, packet)
        }.onFailure { error ->
            warn("[PacketUtil] Failed to send delete packet", error)
        }
    }

    fun sendPremium(player: Player, isPremium: Boolean) {
        sendBooleanPacket(player, CHANNEL_PREMIUM, isPremium)
    }

    fun sendDisplayEnabled(player: Player, isEnabled: Boolean) {
        sendBooleanPacket(player, CHANNEL_DISPLAY_ENABLED, isEnabled)
    }

    fun sendReportEnabled(player: Player, isEnabled: Boolean) {
        sendBooleanPacket(player, CHANNEL_REPORT_ENABLED, isEnabled)
    }

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
        }.onFailure { error ->
            warn("[PacketUtil] Failed to send clear cache packet", error)
        }
    }

    private fun sendBooleanPacket(player: Player, channel: String, value: Boolean) {
        runCatching {
            val packet = buildPacket { output ->
                output.writeBoolean(value)
            }
            player.sendPluginMessage(plugin, channel, packet)
        }.onFailure { error ->
            warn("[PacketUtil] Failed to send $channel packet", error)
        }
    }

    private fun buildPacket(builder: (DataOutputStream) -> Unit): ByteArray {
        return ByteArrayOutputStream().use { byteStream ->
            DataOutputStream(byteStream).use { output ->
                builder(output)
            }
            byteStream.toByteArray()
        }
    }

    private fun sendPacket(players: List<Player?>, channel: String, packet: ByteArray) {
        players.filterNotNull().forEach { player ->
            player.sendPluginMessage(plugin, channel, packet)
        }
    }

    // Extension functions for DataOutputStream for writing
    private fun DataOutputStream.writeUUID(uuid: UUID) {
        writeLong(uuid.mostSignificantBits)
        writeLong(uuid.leastSignificantBits)
    }

    private fun DataOutputStream.writeVarInt(value: Int) {
        var current = value
        while ((current and -0x80) != 0) {
            writeByte((current and 0x7F) or 0x80)
            current = current ushr 7
        }
        writeByte(current and 0x7F)
    }

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

    private fun DataOutputStream.writeString(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        writeVarInt(bytes.size)
        write(bytes)
    }

    private fun BlockFace.toPacketByte(): Byte = when (this) {
        BlockFace.NORTH -> 0
        BlockFace.EAST -> 1
        BlockFace.SOUTH -> 2
        BlockFace.WEST -> 3
        else -> 0
    }

    // Extension functions for DataInputStream for reading
    fun DataInputStream.readUUID(): UUID {
        return UUID(readLong(), readLong())
    }

    fun DataInputStream.readVarInt(): Int {
        var result = 0
        var shift = 0
        var byte: Int

        do {
            if (shift >= 35) throw IOException("[PacketUtil] VarInt too big")

            byte = readUnsignedByte()
            result = result or ((byte and 0x7F) shl shift)
            shift += 7
        } while ((byte and 0x80) != 0)

        return result
    }

    fun DataInputStream.readVarLong(): Long {
        var result = 0L
        var shift = 0
        var byte: Byte

        do {
            if (shift >= 70) throw RuntimeException("[PacketUtil] VarLong too big")

            byte = readByte()
            result = result or ((byte.toInt() and 0x7F).toLong() shl shift)
            shift += 7
        } while ((byte.toInt() and 0x80) != 0)

        return result
    }
}
