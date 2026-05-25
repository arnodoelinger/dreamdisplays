package com.dreamdisplays.managers

import com.dreamdisplays.Main
import com.dreamdisplays.Main.Companion.config
import com.dreamdisplays.Main.Companion.disablePlugin
import com.dreamdisplays.datatypes.DisplayData
import com.dreamdisplays.managers.DisplayManager.register
import com.dreamdisplays.managers.DisplayManager.save
import com.dreamdisplays.meta.Scheduler
import me.inotsleep.utils.logging.LoggingManager.error
import me.inotsleep.utils.storage.connection.BaseConnection
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.jspecify.annotations.NullMarked
import java.nio.ByteBuffer
import java.sql.SQLException
import java.util.*

/**
 * Manages storage and retrieval of display data from the database.
 *
 * `Paper` implementation.
 */
@NullMarked
class StorageManager(var plugin: Main) {
    var connection: BaseConnection? = null
    var tablePrefix: String? = null

    init {
        val connectTask = Runnable {
            tablePrefix = config.storage.tablePrefix
            try {
                connection = BaseConnection.createConnection(config.storage, plugin.dataFolder)
                connection?.connect() ?: run {
                    error("[StorageManager] Failed to create database connection")
                    disablePlugin()
                    return@Runnable
                }
                onConnect()
            } catch (e: SQLException) {
                error("[StorageManager] Could not connect to database", e)
                disablePlugin()
            }
        }

        Scheduler.runAsync(connectTask)
    }

    @Throws(SQLException::class)
    private fun onConnect() {
        val conn = connection ?: throw SQLException("[StorageManager] Connection is null.")

        conn.executeUpdate(
            "CREATE TABLE IF NOT EXISTS " + tablePrefix + "displays (" +
                    "id BINARY(16) PRIMARY KEY NOT NULL, " +
                    "ownerId BINARY(16) NOT NULL, " +
                    "videoCode CHAR(11) NULL, " +
                    "world CHAR(255) NOT NULL, " +
                    "pos1 BIGINT NOT NULL, " +
                    "pos2 BIGINT NOT NULL, " +
                    "size BIGINT NOT NULL, " +
                    "facing TINYINT UNSIGNED NOT NULL, " +
                    "isSync BOOLEAN NOT NULL," +
                    "duration BIGINT NULL" +
                    ");"
        )

        val meta = conn.metaData
        meta.getColumns(null, null, tablePrefix + "displays", "lang").use { cols ->
            if (!cols.next()) {
                conn.executeUpdate(
                    "ALTER TABLE " + tablePrefix + "displays " +
                            "ADD COLUMN lang VARCHAR(255) DEFAULT '' NOT NULL"
                )
            }
        }
        meta.getColumns(null, null, tablePrefix + "displays", "isLocked").use { cols ->
            if (!cols.next()) {
                conn.executeUpdate(
                    "ALTER TABLE " + tablePrefix + "displays " +
                            "ADD COLUMN isLocked BOOLEAN NOT NULL DEFAULT 1"
                )
            }
        }
        register(this.allDisplays.filterNotNull())
    }

    fun onDisable() {
        val conn = connection ?: return

        try {
            save { data: DisplayData -> this.saveDisplay(data) }
            conn.disconnect()
        } catch (e: SQLException) {
            error("[StorageManager] Unable to save data", e)
        }
    }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        return buf.array()
    }

    private fun bytesToUuid(bytes: ByteArray): UUID {
        if (bytes.size != 16) throw IllegalArgumentException("[StorageManager] UUID byte array must be 16 bytes, got ${bytes.size}.")
        val buf = ByteBuffer.wrap(bytes)
        val msb = buf.getLong()
        val lsb = buf.getLong()
        return UUID(msb, lsb)
    }

    fun saveDisplay(data: DisplayData) {
        val conn = connection ?: run {
            error("[StorageManager] Cannot save display: connection is null.")
            return
        }

        val world = data.pos1.world ?: run {
            error("[StorageManager] Cannot save display: world is null for display ${data.id}.")
            return
        }

        val sql = "REPLACE INTO " + tablePrefix + "displays " +
                "(id, ownerId, videoCode, world, pos1, pos2, size, facing, isSync, duration, lang, isLocked) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"

        try {
            conn.executeUpdate(
                sql,
                uuidToBytes(data.id),
                uuidToBytes(data.ownerId),
                data.url,
                world.name,
                packBlockPos(
                    data.pos1.blockX,
                    data.pos1.blockY,
                    data.pos1.blockZ
                ),
                packBlockPos(
                    data.pos2.blockX,
                    data.pos2.blockY,
                    data.pos2.blockZ
                ),
                pack(data.width, data.height),
                data.facing.ordinal.toByte(),
                data.isSync,
                data.duration,
                data.lang,
                data.isLocked
            )
        } catch (e: SQLException) {
            error("[StorageManager] Could not save display to database", e)
            disablePlugin()
        }
    }

    val allDisplays: MutableList<DisplayData?>
        // Fetch all displays from the database
        get() {
            val conn = connection ?: run {
                error("[StorageManager] Cannot fetch displays: connection is null.")
                return mutableListOf()
            }

            val sql =
                "SELECT id, ownerId, videoCode, world, pos1, pos2, size, facing, isSync, duration, lang, isLocked " +
                        "FROM " + tablePrefix + "displays"
            val list: MutableList<DisplayData?> = ArrayList()

            try {
                conn.executeQuery(sql).use { rs ->
                    while (rs.next()) {
                        val idBytes = rs.getBytes("id")
                        if (idBytes == null) {
                            error("[StorageManager] Skipping display row with null id.")
                            continue
                        }
                        val ownerBytes = rs.getBytes("ownerId")
                        if (ownerBytes == null) {
                            error("[StorageManager] Skipping display row with null ownerId.")
                            continue
                        }
                        val id = try {
                            bytesToUuid(idBytes)
                        } catch (e: Exception) {
                            error("[StorageManager] Invalid UUID bytes for display id, skipping row", e)
                            continue
                        }
                        val ownerId = try {
                            bytesToUuid(ownerBytes)
                        } catch (e: Exception) {
                            error("[StorageManager] Invalid UUID bytes for ownerId, skipping row", e)
                            continue
                        }
                        val videoCode = rs.getString("videoCode") ?: ""
                        val worldName = rs.getString("world")
                        if (worldName == null) {
                            error("[StorageManager] Skipping display $id because world name is null. This is very strange.")
                            continue
                        }

                        // Try to resolve world by name first. If that fails, attempt to parse stored
                        // world value as a UUID and resolve by UUID. This helps if worlds are
                        // stored by UUID (or if server uses UUID-based world storage) or when
                        // world names changed between imports.
                        var world = Bukkit.getWorld(worldName)
                        if (world == null) {
                            runCatching { UUID.fromString(worldName) }.getOrNull()?.let { wuid ->
                                world = Bukkit.getWorld(wuid)
                            }
                        }

                        if (world == null) {
                            error("[StorageManager] Skipping display $id because world '$worldName' not found on server.")
                            continue
                        }

                        val packed1 = rs.getLong("pos1")
                        val packed2 = rs.getLong("pos2")
                        val x1: Int = unpackX(packed1)
                        val y1: Int = unpackY(packed1)
                        val z1: Int = unpackZ(packed1)
                        val x2: Int = unpackX(packed2)
                        val y2: Int = unpackY(packed2)
                        val z2: Int = unpackZ(packed2)

                        val sizePacked = rs.getLong("size")
                        val width: Int = unpackHigh(sizePacked)
                        val height: Int = unpackLow(sizePacked)

                        val facingIndex = rs.getInt("facing")
                        val facing = BlockFace.entries.getOrNull(facingIndex) ?: BlockFace.NORTH

                        val data = DisplayData(
                            id, ownerId,
                            Location(world, x1.toDouble(), y1.toDouble(), z1.toDouble()),
                            Location(world, x2.toDouble(), y2.toDouble(), z2.toDouble()),
                            width, height,
                            facing
                        )
                        data.url = videoCode
                        data.isSync = rs.getBoolean("isSync")
                        val dur = rs.getLong("duration")
                        if (!rs.wasNull()) {
                            data.duration = dur
                        }

                        data.lang = rs.getString("lang") ?: ""
                        val isLockedVal = rs.getBoolean("isLocked")
                        data.isLocked = if (rs.wasNull()) true else isLockedVal

                        list.add(data)
                    }
                }
            } catch (e: SQLException) {
                error("[StorageManager] Could not fetch from database", e)
                disablePlugin()
            }

            return list
        }

    fun deleteDisplay(data: DisplayData) {
        val conn = connection ?: run {
            error("[StorageManager] Cannot delete display: connection is null.")
            return
        }

        val sql = "DELETE FROM " + tablePrefix + "displays WHERE id = ?"
        try {
            conn.executeUpdate(sql, uuidToBytes(data.id))
        } catch (e: SQLException) {
            error("[StorageManager] Could not delete display from database", e)
            disablePlugin()
        }
    }

    companion object {
        private fun packBlockPos(x: Int, y: Int, z: Int): Long {
            return ((x and 0x3FFFFFF).toLong() shl 38) or ((z and 0x3FFFFFF).toLong() shl 12) or (y and 0xFFF).toLong()
        }

        private fun unpackX(packed: Long): Int {
            return (packed shr 38).toInt()
        }

        private fun unpackY(packed: Long): Int {
            return (packed shl 52 shr 52).toInt()
        }

        private fun unpackZ(packed: Long): Int {
            return (packed shl 26 shr 38).toInt()
        }

        fun pack(high: Int, low: Int): Long {
            return ((high.toLong()) shl 32) or (low.toLong() and 0xFFFFFFFFL)
        }

        fun unpackHigh(packed: Long): Int {
            return (packed shr 32).toInt()
        }

        fun unpackLow(packed: Long): Int {
            return packed.toInt()
        }
    }
}
